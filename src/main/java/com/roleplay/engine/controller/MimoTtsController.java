package com.roleplay.engine.controller;

import jakarta.annotation.PreDestroy;
import com.roleplay.engine.core.PersonaCardLoader;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.service.MimoTtsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;

/**
 * MiMo TTS 端点（P-0817-A）：角色语音合成 —— 同步合成 / 异步任务 / 内置音色清单 /
 * 运行时状态 / 角色声线配置查询。独立端点，SSE 主链路 / RouterService / 剧本杀 / 狼人杀零触碰；
 * 前端可在收到 AI 对话消息后调用本端点合成该消息语音（「对话 TTS 集成」的独立端点方案）。
 */
@RestController
@RequestMapping("/api/tts/mimo")
public class MimoTtsController {

    private static final Logger log = LoggerFactory.getLogger(MimoTtsController.class);

    /** 异步任务上限（超出淘汰最旧，防内存膨胀）。 */
    private static final int MAX_JOBS = 100;

    private final MimoTtsService tts;
    private final DatabaseService databaseService;
    private final Map<String, CompletableFuture<MimoTtsService.TtsResult>> jobs = new ConcurrentHashMap<>();
    /** 与 jobs 同步维护的 FIFO；ConcurrentHashMap 本身没有插入顺序。 */
    private final ConcurrentLinkedDeque<String> jobOrder = new ConcurrentLinkedDeque<>();
    /** 实际执行槽位；记录淘汰不能替代外部 API 工作量背压。 */
    private final Semaphore asyncSlots = new Semaphore(MAX_JOBS);

    public MimoTtsController(MimoTtsService tts, DatabaseService databaseService) {
        this.tts = tts;
        this.databaseService = databaseService;
    }

    // ── 同步合成 ──────────────────────────────────────────────

    /**
     * POST /api/tts/mimo/synthesize
     * body: {text(必填), mode=basic|clone|design(默认 basic), voice(可选, basic 内置音色名),
     *        voice_data(可选, clone=参考音频路径或 data URL / design=音色描述 / basic=内置音色名),
     *        tone(可选, 语气描述), format=wav(默认), character(可选, 按角色名解析声线配置)}
     * 响应：默认返回原始音频字节（audio/wav）；?json=true 返回 {audio_base64, format, transcript, model, elapsed_ms, mode}
     */
    @PostMapping("/synthesize")
    public ResponseEntity<?> synthesize(@RequestBody(required = false) Map<String, Object> body,
                                        @RequestParam(defaultValue = "false") boolean json) {
        Map<String, Object> b = body == null ? Map.of() : body;
        try {
            String text = str(b.get("text"), "");
            if (text.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "text 不能为空"));
            }
            MimoTtsService.VoiceSpec spec = resolveSpec(b);
            MimoTtsService.TtsResult result = tts.synthesize(text, spec);
            if (json) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("audio_base64", Base64.getEncoder().encodeToString(result.audio()));
                out.put("format", result.format());
                out.put("transcript", result.transcript());
                out.put("model", result.model());
                out.put("elapsed_ms", result.elapsedMs());
                out.put("mode", modeName(spec));
                out.put("bytes", result.audio().length);
                return ResponseEntity.ok(out);
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType("wav".equalsIgnoreCase(result.format())
                    ? MediaType.parseMediaType("audio/wav")
                    : MediaType.parseMediaType("audio/mpeg"));
            headers.set("X-Tts-Mode", modeName(spec));
            headers.set("X-Tts-Model", result.model());
            headers.set("X-Tts-Format", result.format());
            headers.set("Cache-Control", "no-store");
            return ResponseEntity.ok().headers(headers).body(result.audio());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            // 配置类错误（未启用/未配置 key）→ 503；API 调用失败 → 502
            String msg = e.getMessage() == null ? "" : e.getMessage();
            int code = (msg.contains("未启用") || msg.contains("未配置")) ? 503 : 502;
            return ResponseEntity.status(code).body(Map.of("error", e.getMessage()));
        }
    }

    // ── 异步任务 ──────────────────────────────────────────────

    /** POST /api/tts/mimo/synthesize/async —— body 同 /synthesize；立即返回 {job_id}，结果轮询 /result/{id}。 */
    @PostMapping("/synthesize/async")
    public ResponseEntity<?> synthesizeAsync(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String text = str(b.get("text"), "");
        if (text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text 不能为空"));
        }
        if (text.length() > MimoTtsService.MAX_TEXT_CHARS) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "text 不能超过 " + MimoTtsService.MAX_TEXT_CHARS + " 字符"));
        }
        if (!tts.isEnabled()) {
            return ResponseEntity.status(503).body(Map.of("error", "MiMo TTS 未启用（roleplay.tts.mimo.enabled=false）"));
        }
        if (!asyncSlots.tryAcquire()) {
            return ResponseEntity.status(429).body(Map.of("error", "异步 TTS 任务已达上限，请稍后重试"));
        }
        try {
            MimoTtsService.VoiceSpec spec = resolveSpec(b);
            String jobId = UUID.randomUUID().toString();
            CompletableFuture<MimoTtsService.TtsResult> future = tts.synthesizeAsync(text, spec);
            synchronized (jobOrder) {
                while (jobs.size() >= MAX_JOBS) {
                    String eldest = jobOrder.pollFirst();
                    if (eldest == null) break;
                    CompletableFuture<MimoTtsService.TtsResult> evicted = jobs.remove(eldest);
                    if (evicted != null && !evicted.isDone()) {
                        evicted.cancel(true);
                    }
                }
                jobs.put(jobId, future);
                jobOrder.addLast(jobId);
            }
            future.whenComplete((r, ex) -> {
                asyncSlots.release();
                log.debug("MiMo TTS 异步任务 {} 完成: {}", jobId,
                        ex == null ? r.audio().length + " bytes" : "error " + ex);
            });
            return ResponseEntity.ok(Map.of("job_id", jobId, "status", "pending"));
        } catch (IllegalArgumentException e) {
            asyncSlots.release();
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            asyncSlots.release();
            throw e;
        }
    }

    @PreDestroy
    public void shutdown() {
        jobs.values().forEach(future -> future.cancel(true));
        jobs.clear();
        jobOrder.clear();
    }

    /** GET /api/tts/mimo/result/{jobId} —— {status: pending|done|error, audio_base64?, ...}。 */
    @GetMapping("/result/{jobId}")
    public ResponseEntity<?> result(@PathVariable String jobId) {
        CompletableFuture<MimoTtsService.TtsResult> future = jobs.get(jobId);
        if (future == null) {
            return ResponseEntity.status(404).body(Map.of("error", "任务不存在或已过期: " + jobId));
        }
        if (!future.isDone()) {
            return ResponseEntity.ok(Map.of("job_id", jobId, "status", "pending"));
        }
        try {
            MimoTtsService.TtsResult r = future.getNow(null);
            if (r == null) {
                return ResponseEntity.ok(Map.of("job_id", jobId, "status", "pending"));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("job_id", jobId);
            out.put("status", "done");
            out.put("audio_base64", Base64.getEncoder().encodeToString(r.audio()));
            out.put("format", r.format());
            out.put("transcript", r.transcript());
            out.put("model", r.model());
            out.put("elapsed_ms", r.elapsedMs());
            out.put("bytes", r.audio().length);
            return ResponseEntity.ok(out);
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return ResponseEntity.status(502).body(Map.of("job_id", jobId, "status", "error",
                    "error", String.valueOf(cause.getMessage())));
        }
    }

    // ── 查询端点 ──────────────────────────────────────────────

    /** GET /api/tts/mimo/voices —— 内置音色清单（basic 模式可用名）。 */
    @GetMapping("/voices")
    public ResponseEntity<List<String>> voices() {
        return ResponseEntity.ok(tts.builtinVoices());
    }

    /** GET /api/tts/mimo/status —— 运行时状态（不暴露 apiKey）。 */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(tts.statusMap());
    }

    /**
     * GET /api/tts/mimo/voice-config/{characterName} —— 角色声线配置（voice_mode/voice_data/voice
     * 来自角色库，P-0817-A 方案1 落库字段）+ 解析后的合成参数预览。角色不存在 → 404。
     */
    @GetMapping("/voice-config/{characterName}")
    public ResponseEntity<Map<String, Object>> voiceConfig(@PathVariable String characterName) {
        Optional<Map<String, Object>> ch = databaseService.getCharacter(characterName);
        if (ch.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "角色不存在: " + characterName));
        }
        Map<String, Object> c = ch.get();
        String mode = normalizeMode(str(c.get("voice_mode"), "basic"));
        String voiceData = str(c.get("voice_data"), null);
        String voice = str(c.get("voice"), null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("character", characterName);
        out.put("voice_mode", mode);
        out.put("voice_data", voiceData);
        out.put("voice", voice);
        out.put("tts", tts.statusMap());
        return ResponseEntity.ok(out);
    }

    // ── 参数解析 ──────────────────────────────────────────────

    /**
     * 解析声线规格：body 显式参数优先；缺省时按 character 名从角色库解析
     * （voice_mode → mode；voice_data → voice_data；basic 模式 voice_data 兼作内置音色名）。
     */
    private MimoTtsService.VoiceSpec resolveSpec(Map<String, Object> b) {
        String mode = normalizeMode(str(b.get("mode"), null));
        String voice = str(b.get("voice"), null);
        String voiceData = str(b.get("voice_data"), null);
        String tone = str(b.get("tone"), null);
        String format = str(b.get("format"), "wav");

        String character = str(b.get("character"), null);
        if (character != null && !character.isBlank()) {
            // P-0817-N：对局临时角色（如 Gal 预置角色/主控/旁白）不在角色库 → 不报错，降级默认音色；
            // 角色库角色有声线配置则正常解析（剧本杀/自由角色声线仍生效）。
            Optional<Map<String, Object>> ch = databaseService.getCharacter(character);
            if (ch.isPresent()) {
                Map<String, Object> c = ch.get();
                if (mode == null) {
                    mode = normalizeMode(str(c.get("voice_mode"), "basic"));
                }
                if (voiceData == null) {
                    voiceData = str(c.get("voice_data"), null);
                }
            } else if (mode == null) {
                // 角色不存在且未显式指定 mode → 降级 basic（前端对局消息无显式声线时的兜底）
                mode = "basic";
            }
            // P-0817-A：从角色卡读取 TTS 音色描述（tone 未显式指定时）
            if (tone == null) {
                tone = readTtsToneFromCard(character);
            }
        }
        if (mode == null) {
            mode = "basic";
        }
        MimoTtsService.Mode m;
        switch (mode) {
            case "clone" -> {
                m = MimoTtsService.Mode.CLONE;
                if (voiceData == null) {
                    throw new IllegalArgumentException("clone 模式需要 voice_data（参考音频路径或 data URL）");
                }
            }
            case "design" -> {
                m = MimoTtsService.Mode.DESIGN;
                if (voiceData == null) {
                    throw new IllegalArgumentException("design 模式需要 voice_data（音色描述）");
                }
            }
            default -> m = MimoTtsService.Mode.BASIC;
        }
        // basic：voice 缺省时用 voice_data（角色库 basic 声线数据即内置音色名）
        if (m == MimoTtsService.Mode.BASIC && voice == null && voiceData != null) {
            voice = voiceData;
        }
        return new MimoTtsService.VoiceSpec(m, voice, voiceData, tone, format);
    }

    private static String normalizeMode(String mode) {
        if (mode == null) return null;
        String m = mode.trim().toLowerCase();
        if (m.isEmpty() || m.equals("basic")) return "basic";
        if (m.equals("clone") || m.equals("voiceclone")) return "clone";
        if (m.equals("design") || m.equals("voicedesign")) return "design";
        throw new IllegalArgumentException("未知 voice_mode: " + mode + "（可选 basic/clone/design）");
    }

    private static String modeName(MimoTtsService.VoiceSpec spec) {
        return spec.mode() == null ? "basic" : spec.mode().name().toLowerCase();
    }

    private static String str(Object o, String def) {
        if (o == null) return def;
        String s = String.valueOf(o);
        return s.isEmpty() ? def : s;
    }

    /** P-0817-A：从角色卡读取 TTS 音色描述（内存卡优先，回退加载器）。 */
    private String readTtsToneFromCard(String characterName) {
        Map<String, Object> card = PersonaCardLoader.cardFor(characterName);
        if (card != null) {
            Object tone = card.get("ttsTone");
            if (tone != null && !String.valueOf(tone).isBlank()) {
                return String.valueOf(tone);
            }
        }
        return null;
    }
}
