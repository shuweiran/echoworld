package com.roleplay.engine.aiimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleplay.engine.broadcast.SseBroadcaster;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * P-0810-01（本地 ComfyUI + Pony V6 XL 角色表情集预生成）：角色注册表 + 生成任务编排。
 *
 * <ul>
 *   <li><b>角色注册表</b>：内存 Map（id → CharacterProfile），yml {@code roleplay.ai-image.characters}
 *       初始角色启动即注册；POST /api/ai-image/character 运行时注册/更新。</li>
 *   <li><b>生成任务</b>：triggerGenerate 提交线程池异步执行——头像 1 张（portrait 构图，文生图）
 *       + 表情 6 张（happy/angry/sad/surprised/embarrassed/neutral，bust 构图，P-0810-05 起改 img2img：
 *       以 avatar.png 原图非透明版为底图，denoise 可配 roleplay.ai-image.img2img-denoise 默认 0.5，
 *       解决同角色 7 张图脸型漂移）+ 全身立绘 1 张（P-0818-E：fullbody.png，FULLBODY 构图 832×1216，
 *       文生图与 avatar 同源，seed=baseSeed+7），每张走 ComfyUIClient.generateOnce / generateImg2Img
 *       （提交→轮询→下载落盘）；单帧失败跳过继续（log.warn），任务终态按 avatar 成败判定。</li>
 *   <li><b>风格一致性</b>：同一角色固定外貌模板（appearance）+ 固定风格词（style）+
 *       固定 seed（角色 ID 的 SHA-256 稳定 hash，跨重启不变）——同角色所有图风格统一。</li>
 *   <li><b>图片 URL</b>：落盘 {@code outputDir/{characterId}/{frame}.png}，
 *       URL 直接 {@code /ai-images/{characterId}/{frame}.png}（AiImageConfig file: 映射兜底，jar 运行可达）；
 *       imagesOf 扫描磁盘，重启后已生成图自动可见。</li>
 *   <li><b>P-0810-04 自动抠背景</b>：每张生成图保存后自动经 {@link RmbgRemover}（RMBG-1.4 ONNX）
 *       产出透明版 {@code {frame}_t.png}（原图保留 + 透明版并存，供 Gal 立绘叠加）；
 *       模型缺失/失败仅 log.warn 降级保留原图，不影响主流程；imagesOf 的 frame→URL 映射
 *       同时含 {@code {frame}_t} 条目（如 avatar_t），前端立绘可优先用 _t 版。</li>
 *   <li><b>非 NSFW</b>：正向提示词固定前缀 {@code score_9, score_8_up, score_7_up, rating_safe}，
 *       负向提示词含 nsfw/nude/暴力/劣质等拦截词（Pony V6 的 score/rating tag 体系）。</li>
 * </ul>
 */
@Service
public class ImageGenService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenService.class);

    /** 表情集（顺序即生成顺序，也是 imagesOf 返回顺序）。 */
    public static final List<String> EXPRESSIONS = List.of(
            "happy", "angry", "sad", "surprised", "embarrassed", "neutral");

    /** Pony V6 非 NSFW 固定前缀（用户要求 rating_safe，严禁裸漏/成人内容）。 */
    public static final String SCORE_TAGS = "score_9, score_8_up, score_7_up, rating_safe";

    private static final String NEGATIVE_PROMPT = String.join(", ",
            "worst quality", "low quality", "blurry", "jpeg artifacts", "watermark", "signature", "text",
            "nsfw", "nude", "naked", "explicit", "sexual content", "sex", "violence", "gore", "blood",
            "extra limbs", "deformed", "bad anatomy", "bad hands", "missing fingers", "mutated", "disfigured");

    /** 表情英文描述（Pony 吃英文 prompt；与中文展示名解耦，前端可用 status 里的中文名）。 */
    private static final Map<String, String> EXPRESSION_PROMPTS = Map.of(
            "happy", "happy expression, bright smile, cheerful",
            "angry", "angry expression, glaring, furrowed brows",
            "sad", "sad expression, teary eyes, downcast",
            "surprised", "surprised expression, eyes wide open, mouth slightly open",
            "embarrassed", "embarrassed expression, blushing, looking away shyly",
            "neutral", "neutral calm expression, gentle look");

    /** 构图三档（任务书：头像 1:1 / 聊天框半身 / 全身 832×1216）。 */
    public enum Composition {
        PORTRAIT("head and shoulders portrait, centered composition, looking at viewer, simple clean background", 1024, 1024),
        BUST("bust shot, upper body, waist-up portrait, chat window avatar style, clean background", 1024, 1024),
        FULLBODY("full body shot, standing, full outfit visible, portrait orientation", 832, 1216),
        /** P-0810-14：场景背景图（横构图，prompt 含 background/no characters——背景无角色）。 */
        BACKGROUND("pixel art background, scenery, environment, no characters, no people, no animals, empty scene, detailed, wide shot", 1216, 832);

        public final String description;
        public final int width;
        public final int height;

        Composition(String description, int width, int height) {
            this.description = description;
            this.width = width;
            this.height = height;
        }
    }

    /** 角色档案（注册表条目）。 */
    public record CharacterProfile(String id, String name, String appearance, String style) {
    }

    /** 生成任务状态（worker 线程更新，volatile + 不可变快照返回）。 */
    public static final class GenTask {
        public enum Status { IDLE, RUNNING, DONE, FAILED }

        private final String taskId;
        private final String characterId;
        private final long submittedAt;
        private volatile Status status;
        private volatile long finishedAt;
        private volatile String progress = "";      // 当前帧名（avatar/happy/...）或 done
        private volatile String error;

        GenTask(String taskId, String characterId) {
            this.taskId = taskId;
            this.characterId = characterId;
            this.submittedAt = System.currentTimeMillis();
            this.status = Status.RUNNING;
        }

        public String taskId() { return taskId; }
        public String characterId() { return characterId; }
        public long submittedAt() { return submittedAt; }
        public Status status() { return status; }
        public long finishedAt() { return finishedAt; }
        public String progress() { return progress; }
        public String error() { return error; }
    }

    private final Map<String, CharacterProfile> characters = new ConcurrentHashMap<>();
    private final Map<String, GenTask> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final ComfyUIClient comfyClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient externalHttp = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
    private final Path outputRoot;
    private volatile String provider;
    private volatile String externalBaseUrl;
    private volatile String externalApiKey;
    private volatile String externalModel;
    private volatile String externalEndpoint;
    private volatile String loraName;
    /** P-0810-05：表情 img2img 强度（yml roleplay.ai-image.img2img-denoise，默认 0.5）。 */
    private volatile double img2imgDenoise;
    /** P-0810-14：单任务总超时（yml roleplay.ai-image.timeout-seconds，场景背景同步等待上限）。 */
    private volatile int timeoutSeconds;
    /** P-0810-14：场景背景图缓存（scene 键 → URL；相同键不重复生成，内存 + 磁盘双重）。 */
    private final Map<String, String> sceneBackgroundUrls = new ConcurrentHashMap<>();
    /** P-0810-14：场景背景图在途任务（scene 键 → future；并发同键去重只生成一次）。 */
    private final Map<String, java.util.concurrent.CompletableFuture<String>> sceneBackgroundTasks = new ConcurrentHashMap<>();
    /** P-0810-04：抠背景器（可测试注入）。 */
    private RmbgRemover rmbg;
    /** P-0810-04：抠背景总开关（yml roleplay.ai-image.rmbg-enabled，默认 true）。 */
    private volatile boolean rmbgEnabled;
    /** P-0811-G(C-2)：SSE 广播器（生成完成/失败推 ai_image_ready/ai_image_error；测试直构不注入=null 跳过）。 */
    private volatile SseBroadcaster sse;
    /** P-0818-F：角色库注入（recoverOrphan 查真实角色名）。 */
    private volatile com.roleplay.engine.db.repository.CharacterRepository characterRepo;

    public ImageGenService(ComfyUIClient comfyClient, AiImageProperties props) {
        this.comfyClient = comfyClient;
        this.provider = valueOr(props.getProvider(), "comfyui");
        this.externalBaseUrl = props.getExternalBaseUrl();
        this.externalApiKey = props.getExternalApiKey();
        this.externalModel = valueOr(props.getExternalModel(), "gpt-image-1");
        this.externalEndpoint = valueOr(props.getExternalEndpoint(), "/images/generations");
        this.outputRoot = Paths.get(props.getOutputDir()).toAbsolutePath();
        this.loraName = props.getLoraName() == null ? "" : props.getLoraName().trim();
        this.img2imgDenoise = props.getImg2imgDenoise();
        this.timeoutSeconds = Math.max(1, props.getTimeoutSeconds());
        this.rmbg = new RmbgRemover(props);
        this.rmbgEnabled = props.isRmbgEnabled();
        int poolSize = Math.max(1, props.getPoolSize());
        this.executor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "ai-image-gen-" + UUID.randomUUID().toString().substring(0, 8));
            t.setDaemon(true);
            return t;
        });
        for (AiImageProperties.CharacterSeed seed : props.getCharacters()) {
            if (seed.getId() != null && !seed.getId().isBlank()) {
                registerCharacter(seed.getId().trim(), seed.getName(), seed.getAppearance(), seed.getStyle());
            }
        }
        // P-0818-F：扫描 outputDir，自动注册磁盘上已有图片但不在 yml 配置中的角色（跨重启不丢失）
        recoverOrphanCharacters();
    }

    /** P-0810-04：测试注入用（默认由构造器从 props 直构）。 */
    public void setRmbgRemover(RmbgRemover remover) {
        this.rmbg = remover == null ? new RmbgRemover((String) null) : remover;
    }

    /** P-0811-G(C-2)：SSE 广播器注入（Spring 自动装配；测试直构不注入=null 跳过）。 */
    @Autowired(required = false)
    public void setSseBroadcaster(SseBroadcaster broadcaster) {
        this.sse = broadcaster;
    }

    /** P-0818-F：角色库注入（recoverOrphan 查真实角色名；Spring 自动装配）。 */
    @Autowired(required = false)
    public void setCharacterRepository(com.roleplay.engine.db.repository.CharacterRepository repo) {
        this.characterRepo = repo;
    }

    /** P-0810-04：抠背景开关（测试/运行时切换；false=只存原图）。 */
    public void setRmbgEnabled(boolean enabled) {
        this.rmbgEnabled = enabled;
    }

    /** 运行时更新图片生成 provider 设置；后续新任务立即使用。 */
    public void applyRuntimeSettings(AiImageProperties props) {
        if (props == null) return;
        provider = valueOr(props.getProvider(), "comfyui");
        externalBaseUrl = props.getExternalBaseUrl();
        externalApiKey = props.getExternalApiKey();
        externalModel = valueOr(props.getExternalModel(), "gpt-image-1");
        externalEndpoint = valueOr(props.getExternalEndpoint(), "/images/generations");
        comfyClient.setBaseUrl(props.getComfyuiBaseUrl());
        loraName = props.getLoraName() == null ? "" : props.getLoraName().trim();
        img2imgDenoise = Math.max(0, Math.min(1, props.getImg2imgDenoise()));
        timeoutSeconds = Math.max(1, props.getTimeoutSeconds());
        rmbgEnabled = props.isRmbgEnabled();
    }

    public boolean isRmbgEnabled() {
        return rmbgEnabled;
    }

    public String provider() { return provider; }

    // ── 角色注册表 ─────────────────────────────────────────────

    /** P-0818-F：扫描 outputDir，自动注册磁盘上已有图片但不在 yml 配置中的角色（跨重启不丢失）。
     *  规则：outputDir 下有子目录且含 avatar.png → 视为已生成角色。
     *  优先读 _meta.json 获取真实角色名（API 注册时写入），不存在则用目录名作 name。
     *  backgrounds/ 目录跳过。 */
    private void recoverOrphanCharacters() {
        try {
            if (!java.nio.file.Files.isDirectory(outputRoot)) return;
            try (java.util.stream.Stream<java.nio.file.Path> dirs = java.nio.file.Files.list(outputRoot)) {
                dirs.filter(java.nio.file.Files::isDirectory)
                    .filter(d -> !"backgrounds".equals(d.getFileName().toString()))
                    .forEach(d -> {
                        String id = d.getFileName().toString();
                        if (!characters.containsKey(id) && java.nio.file.Files.isRegularFile(d.resolve("avatar.png"))) {
                            // P-0818-F：尝试读 _meta.json 获取真实角色名
                            String name = id;
                            String appearance = "";
                            String style = "retro game character art style, 16-bit pixel art, clean outlines, flat colors";
                            java.nio.file.Path meta = d.resolve("_meta.json");
                            if (java.nio.file.Files.isRegularFile(meta)) {
                                try {
                                    String json = java.nio.file.Files.readString(meta);
                                    // 简单解析（不依赖 Jackson）
                                    name = extractJsonString(json, "name", id);
                                    appearance = extractJsonString(json, "appearance", appearance);
                                    style = extractJsonString(json, "style", style);
                                    log.info("AI 生图角色恢复（meta）: id={} name={}", id, name);
                                } catch (Exception ex) {
                                    log.info("AI 生图角色恢复（meta 读取失败，降级用目录名）: id={} err={}", id, ex.getMessage());
                                }
                            } else {
                                log.info("AI 生图角色恢复（磁盘扫描，无 meta）: id={}", id);
                            }
                            registerCharacter(id, name, appearance, style);
                        }
                    });
            }
        } catch (Exception e) {
            log.warn("AI 生图角色恢复扫描失败（不影响启动）: {}", e.getMessage());
        }
    }

    /** 简单 JSON 字段提取（不依赖 Jackson；仅支持顶层 String 字段）。 */
    private static String extractJsonString(String json, String key, String fallback) {
        String needle = "\"" + key + "\":";
        int idx = json.indexOf(needle);
        if (idx < 0) return fallback;
        int start = json.indexOf('"', idx + needle.length());
        if (start < 0) return fallback;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return fallback;
        return json.substring(start + 1, end);
    }

    /** JSON 字符串转义（双引号、反斜杠、换行）。 */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    
    /** 注册/更新角色（id 已存在则覆盖外貌/风格/名称——同 id 出图目录与 seed 不变）。
     *  P-0818-F：同时写 _meta.json 到角色目录（跨重启恢复用）。 */
    public CharacterProfile registerCharacter(String id, String name, String appearance, String style) {
        CharacterProfile p = new CharacterProfile(id, name, appearance, style);
        characters.put(id, p);
        log.info("AI 生图角色注册: id={} name={} style={}", id, name, style);
        // P-0818-F：写 _meta.json（name/appearance/style），recoverOrphanCharacters 恢复时读取
        try {
            Path dir = outputRoot.resolve(id);
            java.nio.file.Files.createDirectories(dir);
            String meta = String.format(java.util.Locale.ROOT,
                    "{\"id\":\"%s\",\"name\":\"%s\",\"appearance\":\"%s\",\"style\":\"%s\"}",
                    escapeJson(id), escapeJson(name), escapeJson(appearance), escapeJson(style));
            java.nio.file.Files.writeString(dir.resolve("_meta.json"), meta,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            log.debug("AI 生图角色 meta 写入失败（不影响功能）: id={} err={}", id, e.getMessage());
        }
        return p;
    }

    public CharacterProfile getCharacter(String id) {
        return characters.get(id);
    }

    public String loraName() {
        return loraName;
    }

    public List<CharacterProfile> allCharacters() {
        return new ArrayList<>(characters.values());
    }

    // ── 生成任务 ───────────────────────────────────────────────

    /**
     * 触发某角色生成（头像 1 + 表情 6 + 全身立绘 1，异步执行）。
     *
     * @return 新任务；角色不存在返回 null；已有 RUNNING 任务则返回该任务（防重复提交）
     */
    public GenTask triggerGenerate(String characterId) {
        CharacterProfile profile = characters.get(characterId);
        if (profile == null) return null;
        GenTask existing = tasks.get(characterId);
        if (existing != null && existing.status() == GenTask.Status.RUNNING) {
            return existing;
        }
        GenTask task = new GenTask(UUID.randomUUID().toString().substring(0, 12), characterId);
        tasks.put(characterId, task);
        executor.submit(() -> runGeneration(task, profile));
        return task;
    }

    public GenTask taskOf(String characterId) {
        return tasks.get(characterId);
    }

    private void runGeneration(GenTask task, CharacterProfile profile) {
        long baseSeed = stableSeed(profile.id());
        try {
            // 1) 头像（portrait 构图，文生图）——后续表情 img2img 的底图
            task.progress = "avatar";
            genOne(task, profile, "avatar.png",
                    EXPRESSION_PROMPTS.get("neutral") + ", " + Composition.PORTRAIT.description,
                    Composition.PORTRAIT, baseSeed, 0);
            // 2) 表情集（P-0810-05：bust 构图 img2img，底图=avatar.png 原图非透明版，
            //    denoise 可配 roleplay.ai-image.img2img-denoise；单帧失败跳过继续，不拖垮整任务）
            for (int i = 0; i < EXPRESSIONS.size(); i++) {
                String expr = EXPRESSIONS.get(i);
                task.progress = expr;
                try {
                    genExpressionFromAvatar(task, profile, expr + ".png",
                            EXPRESSION_PROMPTS.getOrDefault(expr, EXPRESSION_PROMPTS.get("neutral")),
                            baseSeed, i + 1);
                } catch (Exception e) {
                    log.warn("表情帧 img2img 失败，跳过继续: id={} frame={} err={}", profile.id(), expr, e.getMessage());
                }
            }
            // 3) 全身立绘（P-0818-E：FULLBODY 构图 832×1216，文生图与 avatar 同源；
            //    seed=baseSeed+7（avatar=+0，表情=+1~6，fullbody=+7 避免冲突）；
            //    genOne 内部自动抠背景 → fullbody_t.png；单帧失败跳过继续，不拖垮整任务）
            task.progress = "fullbody";
            try {
                genOne(task, profile, "fullbody.png", Composition.FULLBODY.description,
                        Composition.FULLBODY, baseSeed, EXPRESSIONS.size() + 1);
            } catch (Exception e) {
                log.warn("全身立绘生成失败，跳过继续: id={} err={}", profile.id(), e.getMessage());
            }
            task.progress = "done";
            task.status = GenTask.Status.DONE;
            task.finishedAt = System.currentTimeMillis();
            log.info("AI 生图角色完成: id={} 共 8 张（avatar 文生图 + 6 表情 img2img + fullbody 全身立绘）", profile.id());
            broadcastImageEvent("ai_image_ready", profile.id(), null);
        } catch (Exception e) {
            task.status = GenTask.Status.FAILED;
            task.finishedAt = System.currentTimeMillis();
            task.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("AI 生图角色失败: id={} frame={} err={}", profile.id(), task.progress, task.error);
            broadcastImageEvent("ai_image_error", profile.id(), task.error);
        } catch (Throwable t) {
            // 防御性兜底：worker 任何未预期异常（含 Error）都标记 FAILED，防任务永久卡 RUNNING
            task.status = GenTask.Status.FAILED;
            task.finishedAt = System.currentTimeMillis();
            task.error = "UNCAUGHT: " + t;
            log.error("AI 生图 worker 未捕获异常: id={}", profile.id(), t);
            broadcastImageEvent("ai_image_error", profile.id(), task.error);
        }
    }

    /** P-0811-G(C-2)：任务终态推送 ai_image_ready/ai_image_error（SSE 全局广播；无注入器跳过）。 */
    private void broadcastImageEvent(String event, String characterId, String error) {
        if (sse == null) return;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("characterId", characterId);
            if (error != null && !error.isBlank()) {
                payload.put("error", error);
            } else {
                String url = imagesOf(characterId).get("avatar");
                if (url != null) payload.put("url", url);
                payload.put("frame", "avatar");
                payload.put("type", "avatar");
            }
            sse.broadcast(event, payload);
        } catch (Exception ex) {
            log.warn("AI 生图 SSE 广播失败: event={} id={} err={}", event, characterId, ex.getMessage());
        }
    }

    private void genOne(GenTask task, CharacterProfile profile, String fileName,
                        String frameDesc, Composition comp, long baseSeed, int frameIndex) throws IOException {
        String positive = SCORE_TAGS + ", " + profile.style() + ", " + profile.appearance() + ", " + frameDesc;
        WorkflowSpec spec = new WorkflowSpec(positive, NEGATIVE_PROMPT, baseSeed + frameIndex,
                comp.width, comp.height, loraName, "rp_" + profile.id());
        Path dir = outputRoot.resolve(profile.id());
        List<String> saved = generate(spec, dir, fileName);
        if (saved.isEmpty()) {
            throw new IOException("未产出图片: " + fileName);
        }
        // P-0810-04：每张生成图自动抠背景 → {frame}_t.png 透明版（原图保留 + 透明版并存）
        removeBackgroundIfEnabled(dir, fileName);
    }

    /**
     * P-0810-05：表情帧 img2img 生成（底图 = avatar.png 原图非透明版，denoise 可配）。
     * 正向提示词与文生图同构（score tag + 风格 + 外貌 + 表情 + bust 构图词），
     * 低 denoise 下构图由底图主导，脸型/发型/服装保持一致，仅表情按描述变化。
     */
    private void genExpressionFromAvatar(GenTask task, CharacterProfile profile, String fileName,
                                         String frameDesc, long baseSeed, int frameIndex) throws IOException {
        String positive = SCORE_TAGS + ", " + profile.style() + ", " + profile.appearance()
                + ", " + frameDesc + ", " + Composition.BUST.description;
        WorkflowSpec spec = new WorkflowSpec(positive, NEGATIVE_PROMPT, baseSeed + frameIndex,
                Composition.BUST.width, Composition.BUST.height, loraName, "rp_" + profile.id());
        Path dir = outputRoot.resolve(profile.id());
        Path avatar = dir.resolve("avatar.png");
        if (!Files.isRegularFile(avatar)) {
            throw new IOException("avatar 底图缺失，无法 img2img: " + avatar);
        }
        List<String> saved = isExternalProvider()
                ? generate(spec, dir, fileName)
                : comfyClient.generateImg2Img(spec, avatar, img2imgDenoise, dir, fileName);
        if (saved.isEmpty()) {
            throw new IOException("未产出图片: " + fileName);
        }
        // P-0810-04：img2img 产物同样自动抠背景 → {frame}_t.png
        removeBackgroundIfEnabled(dir, fileName);
    }

    private List<String> generate(WorkflowSpec spec, Path dir, String fileName) throws IOException {
        return isExternalProvider() ? generateExternal(spec, dir, fileName) : comfyClient.generateOnce(spec, dir, fileName);
    }

    private boolean isExternalProvider() {
        return "openai-compatible".equalsIgnoreCase(provider) || "external".equalsIgnoreCase(provider);
    }

    /** OpenAI-compatible image generation：POST /images/generations，兼容 url 与 b64_json 响应。 */
    private List<String> generateExternal(WorkflowSpec spec, Path dir, String fileName) throws IOException {
        if (externalBaseUrl == null || externalBaseUrl.isBlank()) {
            throw new IOException("外部图片 API 未配置 external-base-url");
        }
        if (externalApiKey == null || externalApiKey.isBlank()) {
            externalApiKey = System.getenv("ROLEPLAY_IMAGE_API_KEY");
        }
        if (externalApiKey == null || externalApiKey.isBlank()) {
            throw new IOException("外部图片 API 未配置 API Key");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", externalModel);
        body.put("prompt", spec.positivePrompt() + ". Avoid: " + spec.negativePrompt());
        body.put("size", spec.width() + "x" + spec.height());
        body.put("n", 1);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(joinUrl(externalBaseUrl, externalEndpoint)))
                .timeout(java.time.Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + externalApiKey.trim())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = externalHttp.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("外部图片 API 请求被中断", e);
        }
        if (response.statusCode() >= 300) {
            throw new IOException("外部图片 API HTTP " + response.statusCode() + ": " + excerpt(response.body()));
        }
        JsonNode data = mapper.readTree(response.body()).path("data");
        if (!data.isArray() || data.isEmpty()) throw new IOException("外部图片 API 响应缺少 data");
        JsonNode first = data.get(0);
        byte[] bytes;
        String b64 = first.path("b64_json").asText("");
        if (!b64.isBlank()) {
            bytes = Base64.getDecoder().decode(b64);
        } else {
            String url = first.path("url").asText("");
            if (url.isBlank()) throw new IOException("外部图片 API 响应缺少 url/b64_json");
            HttpRequest download = HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(60)).GET().build();
            try {
                HttpResponse<byte[]> downloaded = externalHttp.send(download, HttpResponse.BodyHandlers.ofByteArray());
                if (downloaded.statusCode() >= 300) throw new IOException("图片下载 HTTP " + downloaded.statusCode());
                bytes = downloaded.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("外部图片下载被中断", e);
            }
        }
        Files.createDirectories(dir);
        Files.write(dir.resolve(fileName), bytes);
        return List.of(fileName);
    }

    private static String joinUrl(String base, String path) {
        String b = base == null ? "" : base.replaceAll("/+$", "");
        String p = path == null ? "" : path.trim();
        return b + (p.startsWith("/") ? p : "/" + p);
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String excerpt(String value) {
        if (value == null) return "";
        return value.length() > 300 ? value.substring(0, 300) : value;
    }

    /** P-0810-04：抠背景（失败仅 log.warn，不影响主流程——RmbgRemover 内部已兜底不抛）。 */
    private void removeBackgroundIfEnabled(Path dir, String fileName) {
        if (!rmbgEnabled) return;
        String base = fileName.toLowerCase(Locale.ROOT).endsWith(".png")
                ? fileName.substring(0, fileName.length() - 4) : fileName;
        Path rgb = dir.resolve(fileName);
        Path transparent = dir.resolve(base + "_t.png");
        try {
            boolean ok = rmbg.removeBackground(rgb, transparent);
            if (ok) {
                log.info("RMBG 抠图完成: {}", transparent);
            } else {
                log.warn("RMBG 抠图跳过/失败（保留原图）: {}", rgb);
            }
        } catch (Exception e) {
            log.warn("RMBG 抠图异常（不影响主流程）: {} err={}", fileName, e.getMessage());
        }
    }

    // ── 图片 URL 管理 ──────────────────────────────────────────

    /**
     * 返回该角色已生成图片的 frame → URL 映射（磁盘扫描；重启后已生成图自动可见）。
     * frame 包括 avatar + fullbody + 各表情名 + 各帧透明版（{@code {frame}_t}，如 avatar_t / fullbody_t，供立绘叠加）；
     * 未生成的帧不在结果中。
     */
    public Map<String, String> imagesOf(String characterId) {
        Map<String, String> urls = new LinkedHashMap<>();
        Path dir = outputRoot.resolve(characterId);
        if (!Files.isDirectory(dir)) return urls;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .sorted()
                    .forEach(p -> {
                        String fn = p.getFileName().toString();
                        String frame = fn.substring(0, fn.length() - 4);
                        if (isKnownFrame(frame)) {
                            urls.put(frame, "/ai-images/" + characterId + "/" + fn);
                        }
                    });
        } catch (IOException e) {
            log.warn("扫描角色图片目录失败: {} err={}", dir, e.getMessage());
        }
        return urls;
    }

    /** 角色全量状态（status 端点用）：档案 + 任务 + 图片。 */
    public Map<String, Object> characterStatus(String characterId) {
        Map<String, Object> out = new LinkedHashMap<>();
        CharacterProfile p = characters.get(characterId);
        if (p == null) return out;
        out.put("id", p.id());
        out.put("name", p.name());
        out.put("appearance", p.appearance());
        out.put("style", p.style());
        out.put("images", imagesOf(characterId));
        GenTask t = tasks.get(characterId);
        if (t != null) {
            out.put("task", Map.of(
                    "taskId", t.taskId(),
                    "status", t.status().name().toLowerCase(Locale.ROOT),
                    "progress", t.progress(),
                    "submittedAt", t.submittedAt(),
                    "finishedAt", t.finishedAt(),
                    "error", t.error() == null ? "" : t.error()));
        }
        return out;
    }

    /** 单角色图片响应（GET /api/ai-image/character/{id}/images）：avatar + fullbody + 各表情 URL。 */
    public Map<String, Object> imagesResponse(String characterId) {
        Map<String, Object> out = new LinkedHashMap<>();
        CharacterProfile p = characters.get(characterId);
        if (p == null) return out;
        Map<String, String> images = imagesOf(characterId);
        Map<String, String> expressions = new LinkedHashMap<>();
        for (String e : EXPRESSIONS) {
            String url = images.get(e);
            if (url != null) expressions.put(e, url);
        }
        out.put("characterId", p.id());
        out.put("name", p.name());
        out.put("avatar", images.get("avatar"));
        // P-0818-E：全身立绘（images map 经 imagesOf 已含 fullbody/fullbody_t，
        // 透明版 fullbody_t 供 Gal 立绘叠加优先使用）
        out.put("fullbody", images.get("fullbody"));
        if (images.get("fullbody_t") != null) {
            out.put("fullbody_t", images.get("fullbody_t"));
        }
        out.put("expressions", expressions);
        out.put("images", images);
        return out;
    }

    // ── P-0810-14 场景背景图（一般模式 AI 自动出对应背景） ─────────────

    /**
     * 场景背景图生成（同步返回 URL）。
     * <ul>
     *   <li><b>入参</b>：scene（场景名/描述）→ 二次元视觉小说风非 NSFW 背景（Pony 文生图：
     *       {@link #SCORE_TAGS} + anime visual novel background + 场景描述 + {@link Composition#BACKGROUND} 构图词
     *       （background/no characters）+ 复用 {@link #NEGATIVE_PROMPT} 负面词）；</li>
     *   <li><b>落盘</b>：{@code outputRoot/backgrounds/{hash}.png}，URL {@code /ai-images/backgrounds/{hash}.png}
     *       （hash = scene 键 SHA-256 稳定 hash，同键同文件同 seed）；</li>
     *   <li><b>缓存</b>：内存 Map + 磁盘双重——文件已存在直接返回（跨重启不重复生成）；
     *       并发同键经 putIfAbsent future 去重，等待在途任务返回同一 url；</li>
     *   <li><b>同步语义</b>：与既有角色生成（异步任务+轮询）不同，本端点契约要求直接返回
     *       {url, scene}——首调阻塞等待生成完成（单任务约 50s，上限 yml timeout-seconds），
     *       缓存命中/并发同键立即返回。</li>
     * </ul>
     *
     * @param scene 场景名/描述（trim 后作为缓存键）
     * @return 图片 URL（/ai-images/backgrounds/{hash}.png）
     * @throws IllegalArgumentException scene 为空白
     * @throws IllegalStateException 生成失败（ComfyUI 不可用/超时等）
     */
    public String sceneBackground(String scene) {
        String key = scene == null ? "" : scene.trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("场景描述不能为空");
        }
        String cached = sceneBackgroundUrls.get(key);
        if (cached != null) return cached;

        String hash = Long.toHexString(stableSeed(key));
        Path dir = outputRoot.resolve("backgrounds");
        Path target = dir.resolve(hash + ".png");
        String url = "/ai-images/backgrounds/" + hash + ".png";
        // 磁盘缓存：文件已存在（含跨重启）直接返回，不重复生成
        if (Files.isRegularFile(target)) {
            sceneBackgroundUrls.put(key, url);
            return url;
        }

        java.util.concurrent.CompletableFuture<String> future =
                sceneBackgroundTasks.computeIfAbsent(key, k -> {
                    java.util.concurrent.CompletableFuture<String> f = new java.util.concurrent.CompletableFuture<>();
                    executor.submit(() -> {
                        try {
                            String u = generateSceneBackgroundFile(k, hash, dir);
                            sceneBackgroundUrls.put(k, u);
                            f.complete(u);
                        } catch (Throwable t) {
                            f.completeExceptionally(t);
                        }
                    });
                    return f;
                });
        try {
            // 等待本任务（或并发同键的在途任务）完成
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            sceneBackgroundTasks.remove(key);
            Throwable cause = (e instanceof java.util.concurrent.ExecutionException ee && ee.getCause() != null)
                    ? ee.getCause() : e;
            String msg = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            throw new IllegalStateException("场景背景图生成失败: " + msg, cause);
        }
    }

    /** 场景背景图文生图（二次元视觉小说背景、无角色；不做 RMBG 抠图——背景图本无角色）。 */
    private String generateSceneBackgroundFile(String scene, String hash, Path dir) throws IOException {
        String positive = SCORE_TAGS + ", anime visual novel background, polished hand-painted 2d illustration, "
                + scene + ", cinematic lighting, no characters, no text, no pixel art, "
                + Composition.BACKGROUND.description;
        WorkflowSpec spec = new WorkflowSpec(positive, NEGATIVE_PROMPT, stableSeed(scene),
                Composition.BACKGROUND.width, Composition.BACKGROUND.height, loraName, "bg_" + hash);
        List<String> saved = comfyClient.generateOnce(spec, dir, hash + ".png");
        if (saved.isEmpty()) {
            throw new IOException("未产出图片: " + hash + ".png");
        }
        return "/ai-images/backgrounds/" + hash + ".png";
    }

    // ── 工具 ───────────────────────────────────────────────────

    /** 角色 ID 稳定 hash（SHA-256 前 8 字节 → 正 long；跨重启/跨 JVM 一致，同角色同 seed）。 */
    public static long stableSeed(String characterId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(characterId.getBytes(StandardCharsets.UTF_8));
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (digest[i] & 0xFFL);
            }
            return v & Long.MAX_VALUE;
        } catch (NoSuchAlgorithmException e) {
            // 理论上不可能（JDK 必含 SHA-256）；兜底用稳定字符串 hash
            return characterId.hashCode() & Long.MAX_VALUE;
        }
    }

    private static boolean isKnownFrame(String frame) {
        // P-0818-E：fullbody（全身立绘）与 avatar/表情同为已知帧
        if ("avatar".equals(frame) || "fullbody".equals(frame) || EXPRESSIONS.contains(frame)) return true;
        // P-0810-04：透明版 {frame}_t（如 avatar_t / happy_t / fullbody_t）同样收录
        if (frame.endsWith("_t")) {
            String base = frame.substring(0, frame.length() - 2);
            return "avatar".equals(base) || "fullbody".equals(base) || EXPRESSIONS.contains(base);
        }
        return false;
    }

    /** 测试/容器关闭用：优雅停线程池。 */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
