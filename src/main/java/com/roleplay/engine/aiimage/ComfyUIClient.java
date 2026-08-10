package com.roleplay.engine.aiimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P-0810-01（本地 ComfyUI + Pony V6 XL）：ComfyUI API 客户端。
 *
 * <p>对接本地 ComfyUI 三个端点：
 * <ol>
 *   <li>POST /prompt —— 提交工作流（body: {prompt: 工作流, client_id: 随机 UUID}），返回 {prompt_id}</li>
 *   <li>GET /history/{prompt_id} —— 轮询执行结果（completed / status_str / outputs.images[]）</li>
 *   <li>GET /view?filename=..&subfolder=..&type=output —— 下载生成图片字节</li>
 * </ol>
 *
 * <p>工作流模板 {@code /ai-image/pony-v6-workflow.json} 内置 Pony V6 XL 出图链路
 * （UNETLoader + CLIPLoader(sdxl, clip_l+clip_g) + VAELoader + 可选 LoraLoader +
 * CLIPTextEncode×2 + EmptyLatentImage + KSampler(30,7,dpmpp_2m,karras) + VAEDecode + SaveImage），
 * 占位符由 {@link #buildWorkflow(WorkflowSpec)} 替换：
 * {@code __POSITIVE__ / __NEGATIVE__ / __SEED__ / __WIDTH__ / __HEIGHT__ / __LORA_NAME__ / __PREFIX__}；
 * lora 名为空时自动改接（LoraLoader 的 model/clip 引用改回 UNETLoader/CLIPLoader 并移除该节点），
 * 保证不装 LoRA 也能出图。
 *
 * <p>P-0810-05 表情 img2img：新增模板 {@code /ai-image/pony-v6-img2img-workflow.json}
 * （CheckpointLoaderSimple(1) + LoraLoader(5) + CLIPSetLastLayer(13) + CLIPTextEncode(7/8) +
 * LoadImage(D) + VAEEncode(F) + KSampler(10, latent_image=[F,0], denoise=__DENOISE__) +
 * VAEDecode(11) + SaveImage(12)），构建入口 {@link #buildImg2ImgWorkflow(WorkflowSpec, String, double)}；
 * 参考图先经 {@link #uploadImage(Path)} 传 ComfyUI /upload/image（multipart）取回 input 目录文件名
 * 再替换 __REF_IMAGE__；节点注释见 resources/ai-image/pony-v6-img2img-README.md。
 *
 * <p>非 Spring 强依赖类：Spring 走 {@link #ComfyUIClient(ObjectMapper, AiImageProperties)}，
 * 测试/直构走 {@link #ComfyUIClient(ObjectMapper, String, int, int)}；无第三方 HTTP 依赖
 * （JDK 21 java.net.http）。
 */
@org.springframework.stereotype.Component
public class ComfyUIClient {

    private static final String TEMPLATE_RESOURCE = "/ai-image/pony-v6-workflow.json";
    private static final String IMG2IMG_TEMPLATE_RESOURCE = "/ai-image/pony-v6-img2img-workflow.json";
    private static final String CLIENT_ID_PREFIX = "roleplay-java-";

    private final ObjectMapper mapper;
    private final String baseUrl;
    private final int timeoutSeconds;
    private final int pollIntervalMs;
    private final HttpClient http;

    /** Spring 装配（@Component，见类注释）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public ComfyUIClient(ObjectMapper mapper, AiImageProperties props) {
        this(mapper, props.getComfyuiBaseUrl(), props.getTimeoutSeconds(), props.getPollIntervalMs());
    }

    /** 直构/测试构造。 */
    public ComfyUIClient(ObjectMapper mapper, String baseUrl, int timeoutSeconds, int pollIntervalMs) {
        this.mapper = mapper;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.pollIntervalMs = Math.max(20, pollIntervalMs);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) return "";
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    // ── 工作流构建（占位符替换，纯函数可单测）──────────────────────────

    /**
     * 加载工作流模板并用 {@link WorkflowSpec} 替换占位符。
     * 返回可直接 POST /prompt 的 prompt 结构（可变 Map，线程内使用）。
     */
    public static Map<String, Object> buildWorkflow(WorkflowSpec spec) {
        Map<String, Object> wf = loadWorkflowTemplate();
        replacePlaceholders(wf, spec);
        applyLoraRewiring(wf, spec.loraName());
        return wf;
    }

    /** 从 classpath 读取工作流模板（每次构建独立副本，防并发替换互扰）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadWorkflowTemplate() {
        return loadTemplate(TEMPLATE_RESOURCE);
    }

    /**
     * P-0810-05：构建 img2img 工作流（表情生成，底图=角色 avatar）。
     * 占位符 {@code __REF_IMAGE__ / __DENOISE__} 与文生图占位符一并替换后做 lora rewiring。
     *
     * @param spec         出图参数（正向/负向提示词、seed、尺寸、lora、prefix）
     * @param refImageName ComfyUI input 目录中的参考图文件名（{@link #uploadImage(Path)} 返回值）
     * @param denoise      img2img 强度（0-1；0.45 实测脸型/发型/服装 100% 保持）
     */
    public static Map<String, Object> buildImg2ImgWorkflow(WorkflowSpec spec, String refImageName, double denoise) {
        Map<String, Object> wf = loadImg2ImgWorkflowTemplate();
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("__REF_IMAGE__", refImageName == null ? "" : refImageName);
        extra.put("__DENOISE__", String.valueOf(denoise));
        replacePlaceholders(wf, spec, extra);
        applyLoraRewiring(wf, spec.loraName());
        return wf;
    }

    /** P-0810-05：从 classpath 读取 img2img 工作流模板（独立副本）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadImg2ImgWorkflowTemplate() {
        return loadTemplate(IMG2IMG_TEMPLATE_RESOURCE);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadTemplate(String resource) {
        try (InputStream in = ComfyUIClient.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("工作流模板资源缺失: " + resource);
            return new ObjectMapper().readValue(in, Map.class);
        } catch (IOException e) {
            throw new IllegalStateException("工作流模板解析失败: " + resource, e);
        }
    }

    /** 递归替换字符串占位符（__SEED__/__WIDTH__/__HEIGHT__/__DENOISE__ 替换为数值）。 */
    static void replacePlaceholders(Object node, WorkflowSpec spec) {
        replacePlaceholders(node, spec, Map.of());
    }

    /**
     * 递归替换字符串占位符（extra 优先于内置占位符表，供 img2img 的 __REF_IMAGE__/__DENOISE__ 使用）。
     * 数值类占位符（seed/宽高/denoise）以 number 形态进入最终 JSON（ComfyUI 期望 number 类型）。
     */
    @SuppressWarnings("unchecked")
    static void replacePlaceholders(Object node, WorkflowSpec spec, Map<String, String> extra) {
        if (node instanceof Map<?, ?> raw) {
            Map<Object, Object> map = (Map<Object, Object>) raw;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String s) {
                    String replaced = switch (s) {
                        case "__POSITIVE__" -> spec.positivePrompt();
                        case "__NEGATIVE__" -> spec.negativePrompt();
                        case "__SEED__" -> String.valueOf(spec.seed());
                        case "__WIDTH__" -> String.valueOf(spec.width());
                        case "__HEIGHT__" -> String.valueOf(spec.height());
                        case "__LORA_NAME__" -> spec.loraName() == null ? "" : spec.loraName();
                        case "__PREFIX__" -> spec.prefix();
                        default -> extra.getOrDefault(s, s);
                    };
                    if (!replaced.equals(s)) {
                        if (s.equals("__SEED__") || s.equals("__WIDTH__") || s.equals("__HEIGHT__")) {
                            map.put(e.getKey(), Long.parseLong(replaced));
                        } else if (s.equals("__DENOISE__")) {
                            map.put(e.getKey(), Double.parseDouble(replaced));
                        } else {
                            map.put(e.getKey(), replaced);
                        }
                    }
                } else {
                    replacePlaceholders(v, spec, extra);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object v : list) replacePlaceholders(v, spec, extra);
        }
    }

    /**
     * LoRA 改接：lora 名为空时，把所有指向 LoraLoader 的引用改回
     * model→UNETLoader（或 CheckpointLoaderSimple slot0）/ clip→CLIPLoader（或 CheckpointLoaderSimple slot1），
     * 并移除 LoraLoader 节点。
     */
    @SuppressWarnings("unchecked")
    static void applyLoraRewiring(Map<String, Object> workflow, String loraName) {
        String loraId = null, unetId = null, clipId = null, ckptId = null;
        for (Map.Entry<String, Object> e : workflow.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> node)) continue;
            String ct = (String) ((Map<String, Object>) node).get("class_type");
            if ("LoraLoader".equals(ct)) loraId = e.getKey();
            else if ("UNETLoader".equals(ct)) unetId = e.getKey();
            else if ("CLIPLoader".equals(ct) || "DualCLIPLoader".equals(ct)) clipId = e.getKey();
            else if ("CheckpointLoaderSimple".equals(ct)) ckptId = e.getKey();
        }
        if (loraId == null) return; // 模板没有 LoraLoader，无需处理
        boolean hasLora = loraName != null && !loraName.isBlank();
        if (hasLora) return;

        for (Object nodeObj : workflow.values()) {
            if (!(nodeObj instanceof Map<?, ?> node)) continue;
            for (Object inputObj : node.values()) {
                if (!(inputObj instanceof Map<?, ?> inputs)) continue;
                for (Object valObj : inputs.values()) {
                    if (valObj instanceof List<?> rawRef && rawRef.size() == 2
                            && loraId.equals(String.valueOf(rawRef.get(0)))) {
                        @SuppressWarnings("unchecked")
                        List<Object> ref = (List<Object>) rawRef;
                        int slot = ((Number) ref.get(1)).intValue();
                        String target = slot == 0
                                ? (unetId != null ? unetId : ckptId)   // model: UNETLoader 或 ckpt slot0
                                : (clipId != null ? clipId : ckptId);  // clip: CLIPLoader 或 ckpt slot1
                        if (target != null) {
                            ref.set(0, target);
                            // UNETLoader/CLIPLoader 输出均只有 slot0；CheckpointLoaderSimple 的 clip 在 slot1
                            ref.set(1, slot == 1 && clipId == null && ckptId != null ? 1 : 0);
                        }
                    }
                }
            }
        }
        workflow.remove(loraId);
    }

    // ── ComfyUI HTTP 调用 ──────────────────────────────────────

    /**
     * 单图生成完整流程：构建工作流 → POST /prompt → 轮询 /history 至完成 →
     * 下载 outputs.images 保存到 outputDir/fileName。
     *
     * @return 实际保存的文件名列表（通常单图 = [fileName]；多图时 fileName、fileName_1、…）
     * @throws IOException 网络/IO 异常
     * @throws IllegalStateException 执行失败（history status_str=error）或超时
     */
    public List<String> generateOnce(WorkflowSpec spec, Path outputDir, String fileName) throws IOException {
        Map<String, Object> workflow = buildWorkflow(spec);
        return finishGeneration(workflow, outputDir, fileName);
    }

    /**
     * P-0810-05：img2img 表情生成完整流程——
     * ① 参考图（avatar 底图）经 /upload/image 上传到 ComfyUI input 目录；
     * ② 构建 img2img 工作流（__REF_IMAGE__=上传返回名、__DENOISE__=denoise）；
     * ③ 提交 → 轮询 /history → 下载（复用 {@link #finishGeneration}）。
     *
     * @param spec         出图参数（正向含表情描述；seed/尺寸/lora/prefix 与文生图同规则）
     * @param refImagePath 参考图本地路径（avatar.png 原图非透明版）
     * @param denoise      img2img 强度（yml roleplay.ai-image.img2img-denoise，默认 0.5）
     * @return 实际保存的文件名列表
     */
    public List<String> generateImg2Img(WorkflowSpec spec, Path refImagePath, double denoise,
                                        Path outputDir, String fileName) throws IOException {
        String refName = uploadImage(refImagePath);
        Map<String, Object> workflow = buildImg2ImgWorkflow(spec, refName, denoise);
        return finishGeneration(workflow, outputDir, fileName);
    }

    /** 提交 → 轮询 → 下载共用后半段（generateOnce / generateImg2Img 复用）。 */
    private List<String> finishGeneration(Map<String, Object> workflow, Path outputDir, String fileName) throws IOException {
        String promptId = submit(workflow);
        JsonNode history = waitForHistory(promptId);
        JsonNode images = extractImages(history, promptId);

        Files.createDirectories(outputDir);
        List<String> saved = new ArrayList<>();
        int i = 0;
        for (JsonNode img : images) {
            String filename = img.path("filename").asText();
            String subfolder = img.path("subfolder").asText();
            String type = img.path("type").asText("output");
            byte[] bytes = download(filename, subfolder, type);
            String name = i == 0 ? fileName : insertBeforeExt(fileName, "_" + i);
            Files.write(outputDir.resolve(name), bytes);
            saved.add(name);
            i++;
        }
        if (saved.isEmpty()) {
            throw new IllegalStateException("ComfyUI 任务完成但未产出图片 (prompt_id=" + promptId + ")");
        }
        return saved;
    }

    /**
     * P-0810-05：参考图上传 ComfyUI /upload/image（multipart/form-data，字段名 image）。
     *
     * @return ComfyUI 返回的文件名（input 目录内，如 {@code heroine_avatar_xxxx.png}），用于 __REF_IMAGE__
     * @throws IOException 参考图不存在/网络异常
     */
    public String uploadImage(Path imagePath) throws IOException {
        if (imagePath == null || !Files.isRegularFile(imagePath)) {
            throw new IOException("参考图不存在或不可读: " + imagePath);
        }
        String boundary = "----RoleplayJava" + UUID.randomUUID().toString().replace("-", "");
        byte[] fileBytes = Files.readAllBytes(imagePath);
        String fileName = imagePath.getFileName().toString();

        ByteArrayOutputStream bos = new ByteArrayOutputStream(fileBytes.length + 512);
        writeMultipartField(bos, boundary, "image", fileName, "image/png", fileBytes);
        bos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] body = bos.toByteArray();

        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/upload/image"))
                .timeout(Duration.ofSeconds(Math.min(60, Math.max(15, timeoutSeconds))))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> resp = sendString(req);
        if (resp.statusCode() >= 300) {
            throw new IllegalStateException("ComfyUI /upload/image HTTP " + resp.statusCode() + ": " + resp.body());
        }
        try {
            JsonNode node = mapper.readTree(resp.body());
            String name = node.path("name").asText(null);
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("ComfyUI /upload/image 响应缺 name: " + resp.body());
            }
            return name;
        } catch (IOException e) {
            throw new IllegalStateException("ComfyUI /upload/image 响应解析失败: " + resp.body(), e);
        }
    }

    /** multipart 单字段写入（Content-Disposition + Content-Type + CRLF + 内容）。 */
    private static void writeMultipartField(ByteArrayOutputStream bos, String boundary, String name,
                                            String fileName, String contentType, byte[] content) throws IOException {
        bos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        bos.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        bos.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bos.write(content);
        bos.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    /** POST /prompt 提交工作流，返回 prompt_id。 */
    public String submit(Map<String, Object> workflow) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", workflow);
        body.put("client_id", CLIENT_ID_PREFIX + UUID.randomUUID());
        String resp = postJson(baseUrl + "/prompt", mapper.writeValueAsString(body));
        try {
            JsonNode node = mapper.readTree(resp);
            String promptId = node.path("prompt_id").asText(null);
            if (promptId == null) throw new IllegalStateException("ComfyUI /prompt 响应缺 prompt_id: " + resp);
            return promptId;
        } catch (IOException e) {
            throw new IllegalStateException("ComfyUI /prompt 响应解析失败: " + resp, e);
        }
    }

    /** 轮询 GET /history/{prompt_id} 直至完成/失败/超时。 */
    public JsonNode waitForHistory(String promptId) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            JsonNode history = getJson(baseUrl + "/history/" + promptId);
            JsonNode entry = history.path(promptId);
            if (!entry.isMissingNode()) {
                JsonNode status = entry.path("status");
                boolean completed = status.path("completed").asBoolean(false);
                String statusStr = status.path("status_str").asText("");
                if (completed) {
                    if ("error".equals(statusStr)) {
                        throw new IllegalStateException("ComfyUI 执行失败 status_str=error (prompt_id=" + promptId + "): "
                                + status.path("messages").toString());
                    }
                    return history;
                }
                if ("error".equals(statusStr)) {
                    throw new IllegalStateException("ComfyUI 执行失败 status_str=error (prompt_id=" + promptId + "): "
                            + status.path("messages").toString());
                }
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("ComfyUI 轮询被中断", ie);
            }
        }
        throw new IllegalStateException("ComfyUI 出图超时（" + timeoutSeconds + "s），请检查 ComfyUI 是否在运行/CLIP 是否就位 (prompt_id=" + promptId + ")");
    }

    /** 从 /history 响应提取 outputs 下所有 images 节点。 */
    JsonNode extractImages(JsonNode history, String promptId) {
        JsonNode outputs = history.path(promptId).path("outputs");
        com.fasterxml.jackson.databind.node.ArrayNode arr = mapper.createArrayNode();
        Iterator<Map.Entry<String, JsonNode>> fields = outputs.fields();
        while (fields.hasNext()) {
            JsonNode imgs = fields.next().getValue().path("images");
            for (JsonNode img : imgs) arr.add(img);
        }
        return arr;
    }

    /** GET /view 下载图片字节。 */
    public byte[] download(String filename, String subfolder, String type) throws IOException {
        if (filename == null || filename.isBlank() || filename.contains("/") || filename.contains("\\")
                || filename.contains("..")) {
            throw new IllegalStateException("ComfyUI 返回非法文件名: " + filename);
        }
        String query = "filename=" + enc(filename)
                + "&subfolder=" + enc(subfolder == null ? "" : subfolder)
                + "&type=" + enc(type == null ? "output" : type);
        return getBytes(baseUrl + "/view?" + query);
    }

    // ── HTTP 工具 ──────────────────────────────────────────────

    private String postJson(String url, String json) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.min(30, Math.max(10, timeoutSeconds))))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = sendString(req);
        if (resp.statusCode() >= 300) {
            throw new IllegalStateException("ComfyUI POST " + url + " HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    private JsonNode getJson(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET().build();
        HttpResponse<String> resp = sendString(req);
        if (resp.statusCode() >= 300) {
            throw new IllegalStateException("ComfyUI GET " + url + " HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return mapper.readTree(resp.body());
    }

    private byte[] getBytes(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET().build();
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ComfyUI 请求被中断: " + req.uri(), ie);
        }
        if (resp.statusCode() >= 300) {
            throw new IllegalStateException("ComfyUI GET " + url + " HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    private HttpResponse<String> sendString(HttpRequest req) throws IOException {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ComfyUI 请求被中断: " + req.uri(), ie);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String insertBeforeExt(String name, String suffix) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return name + suffix;
        return name.substring(0, dot) + suffix + name.substring(dot);
    }
}
