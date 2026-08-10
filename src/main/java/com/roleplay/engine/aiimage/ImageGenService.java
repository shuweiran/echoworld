package com.roleplay.engine.aiimage;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
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
 *       解决同角色 7 张图脸型漂移），每张走 ComfyUIClient.generateOnce / generateImg2Img
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
    private final Path outputRoot;
    private final String loraName;
    /** P-0810-05：表情 img2img 强度（yml roleplay.ai-image.img2img-denoise，默认 0.5）。 */
    private final double img2imgDenoise;
    /** P-0810-14：单任务总超时（yml roleplay.ai-image.timeout-seconds，场景背景同步等待上限）。 */
    private final int timeoutSeconds;
    /** P-0810-14：场景背景图缓存（scene 键 → URL；相同键不重复生成，内存 + 磁盘双重）。 */
    private final Map<String, String> sceneBackgroundUrls = new ConcurrentHashMap<>();
    /** P-0810-14：场景背景图在途任务（scene 键 → future；并发同键去重只生成一次）。 */
    private final Map<String, java.util.concurrent.CompletableFuture<String>> sceneBackgroundTasks = new ConcurrentHashMap<>();
    /** P-0810-04：抠背景器（可测试注入）。 */
    private RmbgRemover rmbg;
    /** P-0810-04：抠背景总开关（yml roleplay.ai-image.rmbg-enabled，默认 true）。 */
    private volatile boolean rmbgEnabled;

    public ImageGenService(ComfyUIClient comfyClient, AiImageProperties props) {
        this.comfyClient = comfyClient;
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
    }

    /** P-0810-04：测试注入用（默认由构造器从 props 直构）。 */
    public void setRmbgRemover(RmbgRemover remover) {
        this.rmbg = remover == null ? new RmbgRemover((String) null) : remover;
    }

    /** P-0810-04：抠背景开关（测试/运行时切换；false=只存原图）。 */
    public void setRmbgEnabled(boolean enabled) {
        this.rmbgEnabled = enabled;
    }

    public boolean isRmbgEnabled() {
        return rmbgEnabled;
    }

    // ── 角色注册表 ─────────────────────────────────────────────

    /** 注册/更新角色（id 已存在则覆盖外貌/风格/名称——同 id 出图目录与 seed 不变）。 */
    public CharacterProfile registerCharacter(String id, String name, String appearance, String style) {
        CharacterProfile p = new CharacterProfile(id, name, appearance, style);
        characters.put(id, p);
        log.info("AI 生图角色注册: id={} name={} style={}", id, name, style);
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
     * 触发某角色生成（头像 1 + 表情 6，异步执行）。
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
            task.progress = "done";
            task.status = GenTask.Status.DONE;
            task.finishedAt = System.currentTimeMillis();
            log.info("AI 生图角色完成: id={} 共 7 张（avatar 文生图 + 6 表情 img2img）", profile.id());
        } catch (Exception e) {
            task.status = GenTask.Status.FAILED;
            task.finishedAt = System.currentTimeMillis();
            task.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("AI 生图角色失败: id={} frame={} err={}", profile.id(), task.progress, task.error);
        } catch (Throwable t) {
            // 防御性兜底：worker 任何未预期异常（含 Error）都标记 FAILED，防任务永久卡 RUNNING
            task.status = GenTask.Status.FAILED;
            task.finishedAt = System.currentTimeMillis();
            task.error = "UNCAUGHT: " + t;
            log.error("AI 生图 worker 未捕获异常: id={}", profile.id(), t);
        }
    }

    private void genOne(GenTask task, CharacterProfile profile, String fileName,
                        String frameDesc, Composition comp, long baseSeed, int frameIndex) throws IOException {
        String positive = SCORE_TAGS + ", " + profile.style() + ", " + profile.appearance() + ", " + frameDesc;
        WorkflowSpec spec = new WorkflowSpec(positive, NEGATIVE_PROMPT, baseSeed + frameIndex,
                comp.width, comp.height, loraName, "rp_" + profile.id());
        Path dir = outputRoot.resolve(profile.id());
        List<String> saved = comfyClient.generateOnce(spec, dir, fileName);
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
        List<String> saved = comfyClient.generateImg2Img(spec, avatar, img2imgDenoise, dir, fileName);
        if (saved.isEmpty()) {
            throw new IOException("未产出图片: " + fileName);
        }
        // P-0810-04：img2img 产物同样自动抠背景 → {frame}_t.png
        removeBackgroundIfEnabled(dir, fileName);
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
     * frame 包括 avatar + 各表情名 + 各帧透明版（{@code {frame}_t}，如 avatar_t，供立绘叠加）；
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

    /** 单角色图片响应（GET /api/ai-image/character/{id}/images）：avatar + 各表情 URL。 */
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
        out.put("expressions", expressions);
        out.put("images", images);
        return out;
    }

    // ── P-0810-14 场景背景图（一般模式 AI 自动出对应背景） ─────────────

    /**
     * 场景背景图生成（同步返回 URL）。
     * <ul>
     *   <li><b>入参</b>：scene（场景名/描述）→ 像素风非 NSFW 背景（Pony 文生图：
     *       {@link #SCORE_TAGS} + pixel art + 场景描述 + {@link Composition#BACKGROUND} 构图词
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

    /** 场景背景图文生图（像素风、无角色；不做 RMBG 抠图——背景图本无角色）。 */
    private String generateSceneBackgroundFile(String scene, String hash, Path dir) throws IOException {
        String positive = SCORE_TAGS + ", pixel art, " + scene + ", " + Composition.BACKGROUND.description;
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
        if ("avatar".equals(frame) || EXPRESSIONS.contains(frame)) return true;
        // P-0810-04：透明版 {frame}_t（如 avatar_t / happy_t）同样收录
        if (frame.endsWith("_t")) {
            String base = frame.substring(0, frame.length() - 2);
            return "avatar".equals(base) || EXPRESSIONS.contains(base);
        }
        return false;
    }

    /** 测试/关闭用：优雅停线程池。 */
    public void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
