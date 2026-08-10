package com.roleplay.engine.aiimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-0810-01（本地 ComfyUI + Pony V6 XL）：ComfyUIClient 验收。
 *
 * <p>① 工作流占位符替换（__POSITIVE__/__SEED__/__WIDTH__/__HEIGHT__/__LORA_NAME__/__PREFIX__）
 * ② Lora 名为空自动改接（引用回 CheckpointLoaderSimple 的 model/clip + 移除 LoraLoader 节点）
 * ③ 本地 HttpServer mock ComfyUI：/prompt → /history 轮询 → /view 下载全链路出图
 * ④ 错误态（status_str=error）与超时（completed=false 恒不完成）如实抛错
 */
class ComfyUIClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static Object at(Map<String, Object> wf, Object... path) {
        Object cur = wf;
        for (Object p : path) {
            if (cur instanceof Map<?, ?> m) cur = m.get(p);
            else if (cur instanceof List<?> l) cur = l.get(((Number) p).intValue());
        }
        return cur;
    }

    @Test
    @DisplayName("C-1 工作流模板结构：Pony V6 XL 全链路节点齐备")
    void templateStructure() {
        Map<String, Object> wf = ComfyUIClient.loadWorkflowTemplate();
        // P-0810-01 修复（CheckpointLoaderSimple 直接加载完整 Pony，clip skip 2）
        assertEquals("CheckpointLoaderSimple", at(wf, "1", "class_type"));
        assertEquals("PonyDiffusionV6XL.safetensors", at(wf, "1", "inputs", "ckpt_name"));
        assertEquals("LoraLoader", at(wf, "5", "class_type"));
        assertEquals(List.of("1", 0), at(wf, "5", "inputs", "model"));
        assertEquals(List.of("1", 1), at(wf, "5", "inputs", "clip"));
        // 新版 ComfyUI（0.31）Pony 必须 clip skip 2：CLIPSetLastLayer(stop_at_clip_layer=-2)
        assertEquals("CLIPSetLastLayer", at(wf, "13", "class_type"));
        assertEquals(-2, ((Number) at(wf, "13", "inputs", "stop_at_clip_layer")).intValue());
        assertEquals(List.of("5", 1), at(wf, "13", "inputs", "clip"));
        assertEquals("KSampler", at(wf, "10", "class_type"));
        assertEquals(30, at(wf, "10", "inputs", "steps"));
        assertEquals(7.0, ((Number) at(wf, "10", "inputs", "cfg")).doubleValue());
        assertEquals("dpmpp_2m", at(wf, "10", "inputs", "sampler_name"));
        assertEquals("karras", at(wf, "10", "inputs", "scheduler"));
        assertEquals("VAEDecode", at(wf, "11", "class_type"));
        assertEquals(List.of("1", 2), at(wf, "11", "inputs", "vae"));
        assertEquals("SaveImage", at(wf, "12", "class_type"));
        // 关键连线
        assertEquals(List.of("5", 0), at(wf, "10", "inputs", "model"));
        assertEquals(List.of("13", 0), at(wf, "7", "inputs", "clip"));
        assertEquals(List.of("13", 0), at(wf, "8", "inputs", "clip"));
        assertEquals(List.of("11", 0), at(wf, "12", "inputs", "images"));
    }

    @Test
    @DisplayName("C-2 占位符替换：prompt/seed/宽高/lora/prefix 全部替换且类型正确")
    void placeholderReplacement() {
        Map<String, Object> wf = ComfyUIClient.buildWorkflow(new WorkflowSpec(
                "score_9, anime style, 银发少女, happy expression",
                "nsfw, worst quality", 123456789L, 1024, 1024,
                "pixel_art_sakuemonq_pony.safetensors", "rp_heroine"));
        assertEquals("score_9, anime style, 银发少女, happy expression", at(wf, "7", "inputs", "text"));
        assertEquals("nsfw, worst quality", at(wf, "8", "inputs", "text"));
        assertEquals(123456789L, ((Number) at(wf, "10", "inputs", "seed")).longValue());
        assertEquals(1024L, ((Number) at(wf, "9", "inputs", "width")).longValue());
        assertEquals(1024L, ((Number) at(wf, "9", "inputs", "height")).longValue());
        assertEquals("pixel_art_sakuemonq_pony.safetensors", at(wf, "5", "inputs", "lora_name"));
        assertEquals("rp_heroine", at(wf, "12", "inputs", "filename_prefix"));
        assertTrue(ComfyUIClient.loadWorkflowTemplate().get("7") != wf.get("7"),
                "每次构建应返回独立副本（防并发替换互扰）");
    }

    @Test
    @DisplayName("C-3 Lora 名为空：引用改接 CheckpointLoaderSimple + 移除 LoraLoader 节点")
    void loraBlankRewires() {
        Map<String, Object> wf = ComfyUIClient.buildWorkflow(new WorkflowSpec(
                "pos", "neg", 1L, 512, 512, "", "rp"));
        assertNull(wf.get("5"), "Lora 为空应移除 LoraLoader 节点");
        assertEquals(List.of("1", 0), at(wf, "10", "inputs", "model"), "model 改接 CheckpointLoaderSimple(1) slot0");
        assertEquals(List.of("1", 1), at(wf, "13", "inputs", "clip"), "CLIPSetLastLayer(13) clip 改接 CheckpointLoaderSimple(1) slot1");
        assertEquals(List.of("13", 0), at(wf, "7", "inputs", "clip"), "7/8 仍引用 CLIPSetLastLayer(13)");
        assertEquals(List.of("13", 0), at(wf, "8", "inputs", "clip"));
        // 非空 lora 时引用保持
        Map<String, Object> wf2 = ComfyUIClient.buildWorkflow(new WorkflowSpec(
                "pos", "neg", 1L, 512, 512, "lora.safetensors", "rp"));
        assertEquals(List.of("5", 0), at(wf2, "10", "inputs", "model"));
        assertNotNull(wf2.get("5"));
    }

    @Test
    @DisplayName("C-4 端到端：本地 HttpServer mock 全链路（提交→轮询→下载落盘）")
    void generateOnceEndToEnd() throws Exception {
        byte[] png = {1, 2, 3, 4, 5, 6, 7, 8};
        AtomicReference<String> postedBody = new AtomicReference<>();
        AtomicReference<String> viewQuery = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/prompt", ex -> {
            postedBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, 200, "{\"prompt_id\":\"p-abc\"}");
        });
        server.createContext("/history/p-abc", ex ->
                respond(ex, 200, "{\"p-abc\":{\"status\":{\"completed\":true,\"status_str\":\"success\"},"
                        + "\"outputs\":{\"12\":{\"images\":[{\"filename\":\"ComfyUI_00001_.png\","
                        + "\"subfolder\":\"\",\"type\":\"output\"}]}}}}"));
        server.createContext("/view", ex -> {
            viewQuery.set(ex.getRequestURI().getQuery());
            respondBytes(ex, 200, png, "image/png");
        });
        server.start();
        try {
            ComfyUIClient client = new ComfyUIClient(mapper,
                    "http://127.0.0.1:" + server.getAddress().getPort(), 10, 50);
            Path dir = Files.createTempDirectory("comfy-e2e");
            List<String> saved = client.generateOnce(new WorkflowSpec(
                    "score_9, pos", "neg", 42L, 1024, 1024, "lora.safetensors", "rp_t"), dir, "avatar.png");

            assertEquals(List.of("avatar.png"), saved);
            assertArrayEquals(png, Files.readAllBytes(dir.resolve("avatar.png")));
            // 提交 body：prompt + client_id + 占位符已替换
            JsonNode posted = mapper.readTree(postedBody.get());
            assertTrue(posted.has("client_id"));
            assertEquals("score_9, pos", posted.at("/prompt/7/inputs/text").asText());
            assertEquals(42L, posted.at("/prompt/10/inputs/seed").asLong());
            assertEquals(1024, posted.at("/prompt/9/inputs/width").asInt());
            // /view 查询参数正确
            assertNotNull(viewQuery.get());
            assertTrue(viewQuery.get().contains("filename=ComfyUI_00001_.png"), viewQuery.get());
            assertTrue(viewQuery.get().contains("type=output"), viewQuery.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("C-5 执行失败（status_str=error）如实抛错")
    void historyErrorThrows() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/prompt", ex -> respond(ex, 200, "{\"prompt_id\":\"p-err\"}"));
        server.createContext("/history/p-err", ex ->
                respond(ex, 200, "{\"p-err\":{\"status\":{\"completed\":true,\"status_str\":\"error\"}}}"));
        server.start();
        try {
            ComfyUIClient client = new ComfyUIClient(mapper,
                    "http://127.0.0.1:" + server.getAddress().getPort(), 5, 50);
            Path dir = Files.createTempDirectory("comfy-err");
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> client.generateOnce(new WorkflowSpec("p", "n", 1L, 512, 512, "", "rp"), dir, "a.png"));
            assertTrue(e.getMessage().contains("error"), e.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("C-6 轮询超时（completed 恒 false）抛超时异常")
    void historyTimeoutThrows() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/prompt", ex -> respond(ex, 200, "{\"prompt_id\":\"p-slow\"}"));
        server.createContext("/history/p-slow", ex ->
                respond(ex, 200, "{\"p-slow\":{\"status\":{\"completed\":false,\"status_str\":\"running\"}}}"));
        server.start();
        try {
            ComfyUIClient client = new ComfyUIClient(mapper,
                    "http://127.0.0.1:" + server.getAddress().getPort(), 1, 50);
            Path dir = Files.createTempDirectory("comfy-slow");
            long start = System.currentTimeMillis();
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> client.generateOnce(new WorkflowSpec("p", "n", 1L, 512, 512, "", "rp"), dir, "a.png"));
            assertTrue(e.getMessage().contains("超时"), e.getMessage());
            assertTrue(System.currentTimeMillis() - start < 5000, "应在 1s 超时附近抛错");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("C-7 seed 稳定性：同角色 ID hash 跨调用一致")
    void stableSeedDeterministic() {
        assertEquals(ImageGenService.stableSeed("heroine"), ImageGenService.stableSeed("heroine"));
        assertNotEquals(ImageGenService.stableSeed("heroine"), ImageGenService.stableSeed("knight"));
        assertTrue(ImageGenService.stableSeed("heroine") >= 0);
    }

    // ── P-0810-05 img2img ────────────────────────────────────────────

    @Test
    @DisplayName("I-1 img2img 模板结构：LoadImage(D)+VAEEncode(F) 接 KSampler latent，denoise 占位符")
    void img2imgTemplateStructure() {
        Map<String, Object> wf = ComfyUIClient.loadImg2ImgWorkflowTemplate();
        // 与文生图同构的节点
        assertEquals("CheckpointLoaderSimple", at(wf, "1", "class_type"));
        assertEquals("LoraLoader", at(wf, "5", "class_type"));
        assertEquals(List.of("1", 0), at(wf, "5", "inputs", "model"));
        assertEquals(List.of("1", 1), at(wf, "5", "inputs", "clip"));
        assertEquals("CLIPSetLastLayer", at(wf, "13", "class_type"));
        assertEquals(-2, ((Number) at(wf, "13", "inputs", "stop_at_clip_layer")).intValue());
        assertEquals(List.of("5", 1), at(wf, "13", "inputs", "clip"));
        assertEquals("CLIPTextEncode", at(wf, "7", "class_type"));
        assertEquals("CLIPTextEncode", at(wf, "8", "class_type"));
        // img2img 新增：LoadImage + VAEEncode
        assertEquals("LoadImage", at(wf, "D", "class_type"));
        assertEquals("__REF_IMAGE__", at(wf, "D", "inputs", "image"));
        assertEquals("VAEEncode", at(wf, "F", "class_type"));
        assertEquals(List.of("D", 0), at(wf, "F", "inputs", "pixels"));
        assertEquals(List.of("1", 2), at(wf, "F", "inputs", "vae"));
        // KSampler：latent 接 VAEEncode(F)，denoise 为占位符；无 EmptyLatentImage(9)
        assertEquals("KSampler", at(wf, "10", "class_type"));
        assertEquals(List.of("F", 0), at(wf, "10", "inputs", "latent_image"));
        assertEquals("__DENOISE__", at(wf, "10", "inputs", "denoise"));
        assertEquals(List.of("5", 0), at(wf, "10", "inputs", "model"));
        assertNull(wf.get("9"), "img2img 模板不应含 EmptyLatentImage(9)");
        // 收尾节点同文生图
        assertEquals("VAEDecode", at(wf, "11", "class_type"));
        assertEquals(List.of("1", 2), at(wf, "11", "inputs", "vae"));
        assertEquals(List.of("10", 0), at(wf, "11", "inputs", "samples"));
        assertEquals("SaveImage", at(wf, "12", "class_type"));
        assertEquals(List.of("11", 0), at(wf, "12", "inputs", "images"));
        assertEquals("__PREFIX__", at(wf, "12", "inputs", "filename_prefix"));
    }

    @Test
    @DisplayName("I-2 img2img 占位符替换：__REF_IMAGE__/__DENOISE__/lora rewiring")
    void img2imgPlaceholderReplacement() {
        Map<String, Object> wf = ComfyUIClient.buildImg2ImgWorkflow(new WorkflowSpec(
                "score_9, pos img2img", "nsfw, neg", 777L, 1024, 1024,
                "pixel_art_sakuemonq_pony.safetensors", "rp_heroine"), "heroine_avatar_1234.png", 0.45);
        assertEquals("heroine_avatar_1234.png", at(wf, "D", "inputs", "image"));
        assertEquals(0.45, ((Number) at(wf, "10", "inputs", "denoise")).doubleValue());
        assertEquals("score_9, pos img2img", at(wf, "7", "inputs", "text"));
        assertEquals("nsfw, neg", at(wf, "8", "inputs", "text"));
        assertEquals(777L, ((Number) at(wf, "10", "inputs", "seed")).longValue());
        assertEquals("rp_heroine", at(wf, "12", "inputs", "filename_prefix"));
        assertEquals(List.of("5", 0), at(wf, "10", "inputs", "model"));
        assertNotNull(wf.get("5"));

        // lora 为空：移除 LoraLoader(5)，model/clip 引用改接 CheckpointLoaderSimple(1)
        Map<String, Object> wf2 = ComfyUIClient.buildImg2ImgWorkflow(new WorkflowSpec(
                "pos", "neg", 1L, 512, 512, "", "rp"), "ref.png", 0.5);
        assertNull(wf2.get("5"));
        assertEquals(List.of("1", 0), at(wf2, "10", "inputs", "model"));
        assertEquals(List.of("1", 1), at(wf2, "13", "inputs", "clip"));
        assertEquals(List.of("13", 0), at(wf2, "7", "inputs", "clip"));
        assertEquals(List.of("13", 0), at(wf2, "8", "inputs", "clip"));
        // F/11 的 vae 引用不受 rewiring 影响（CheckpointLoaderSimple slot2）
        assertEquals(List.of("1", 2), at(wf2, "F", "inputs", "vae"));
        assertEquals(List.of("1", 2), at(wf2, "11", "inputs", "vae"));
        // denoise 数值类型（Double 而非字符串）
        assertTrue(at(wf2, "10", "inputs", "denoise") instanceof Number);
        // 每次构建独立副本
        assertTrue(ComfyUIClient.loadImg2ImgWorkflowTemplate().get("D") != wf.get("D"));
    }

    @Test
    @DisplayName("I-3 端到端 img2img：/upload/image multipart 上传 → 工作流带 ref/denoise → 轮询 → 下载")
    void generateImg2ImgEndToEnd() throws Exception {
        byte[] png = {1, 2, 3, 4, 5, 6, 7, 8};
        byte[] refBytes = {9, 9, 9, 9};
        AtomicReference<String> uploadBody = new AtomicReference<>();
        AtomicReference<String> uploadContentType = new AtomicReference<>();
        AtomicReference<String> postedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/upload/image", ex -> {
            uploadContentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            uploadBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            respond(ex, 200, "{\"name\":\"heroine_avatar_1234.png\",\"subfolder\":\"\",\"type\":\"input\"}");
        });
        server.createContext("/prompt", ex -> {
            postedBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, 200, "{\"prompt_id\":\"p-img2img\"}");
        });
        server.createContext("/history/p-img2img", ex ->
                respond(ex, 200, "{\"p-img2img\":{\"status\":{\"completed\":true,\"status_str\":\"success\"},"
                        + "\"outputs\":{\"12\":{\"images\":[{\"filename\":\"ComfyUI_00002_.png\","
                        + "\"subfolder\":\"\",\"type\":\"output\"}]}}}}"));
        server.createContext("/view", ex -> respondBytes(ex, 200, png, "image/png"));
        server.start();
        try {
            ComfyUIClient client = new ComfyUIClient(mapper,
                    "http://127.0.0.1:" + server.getAddress().getPort(), 10, 50);
            Path dir = Files.createTempDirectory("comfy-img2img");
            Path ref = dir.resolve("avatar.png");
            Files.write(ref, refBytes);

            List<String> saved = client.generateImg2Img(new WorkflowSpec(
                    "score_9, happy expression, bust shot", "neg", 99L, 1024, 1024,
                    "lora.safetensors", "rp_heroine"), ref, 0.45, dir, "happy.png");

            assertEquals(List.of("happy.png"), saved);
            assertArrayEquals(png, Files.readAllBytes(dir.resolve("happy.png")));
            // ① multipart 上传：Content-Type 含 boundary、body 含文件字节与 filename
            assertNotNull(uploadContentType.get());
            assertTrue(uploadContentType.get().startsWith("multipart/form-data; boundary="), uploadContentType.get());
            assertTrue(uploadBody.get().contains("filename=\"avatar.png\""), "multipart 应带文件名");
            assertTrue(uploadBody.get().contains("Content-Type: image/png"), "multipart 应带 content-type");
            assertTrue(uploadBody.get().contains(new String(refBytes, StandardCharsets.ISO_8859_1)), "multipart 应含图片字节");
            // ② 提交的工作流：LoadImage 用上传返回名、denoise 数值化
            JsonNode posted = mapper.readTree(postedBody.get());
            assertEquals("heroine_avatar_1234.png", posted.at("/prompt/D/inputs/image").asText());
            assertEquals(0.45, posted.at("/prompt/10/inputs/denoise").asDouble(), 1e-9);
            assertEquals("F", posted.at("/prompt/10/inputs/latent_image/0").asText());
            assertEquals(0, posted.at("/prompt/10/inputs/latent_image/1").asInt());
            assertEquals("score_9, happy expression, bust shot", posted.at("/prompt/7/inputs/text").asText());
            assertEquals(99L, posted.at("/prompt/10/inputs/seed").asLong());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("I-4 参考图缺失 / /upload/image 返回缺 name → 如实抛错")
    void uploadImageErrors() throws Exception {
        Path dir = Files.createTempDirectory("comfy-img2img-err");
        // 参考图不存在
        ComfyUIClient client = new ComfyUIClient(mapper, "http://127.0.0.1:1", 5, 50);
        assertThrows(IOException.class, () -> client.uploadImage(dir.resolve("nope.png")));
        assertThrows(IOException.class,
                () -> client.generateImg2Img(new WorkflowSpec("p", "n", 1L, 512, 512, "", "rp"),
                        dir.resolve("nope.png"), 0.5, dir, "a.png"));

        // /upload/image 响应缺 name
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/upload/image", ex -> respond(ex, 200, "{\"subfolder\":\"\"}"));
        server.start();
        try {
            ComfyUIClient c2 = new ComfyUIClient(mapper,
                    "http://127.0.0.1:" + server.getAddress().getPort(), 5, 50);
            Path ref = dir.resolve("avatar.png");
            Files.write(ref, new byte[]{1, 2});
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> c2.uploadImage(ref));
            assertTrue(e.getMessage().contains("name"), e.getMessage());
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static void respondBytes(HttpExchange ex, int code, byte[] bytes, String contentType) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
