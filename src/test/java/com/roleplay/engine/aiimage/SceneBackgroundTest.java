package com.roleplay.engine.aiimage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-0810-14：场景背景图生成端点验收（mock ComfyUI 客户端）。
 *
 * <p>① 生成成功：url 契约 /ai-images/backgrounds/{hash}.png + 文件落盘 backgrounds/ + prompt 契约
 * （SCORE_TAGS 含 rating_safe + pixel art + background/no characters + 负向 nsfw 拦截）+ 横构图 1216×832；
 * ② 同 scene 键缓存（内存）：二次调用不触发生成，直接返回相同 url；
 * ③ 不同 scene → 不同 hash 文件；
 * ④ 磁盘缓存：文件已存在（跨重启）不重复生成直接返回；
 * ⑤ 并发同键去重：多线程同时请求同 scene，ComfyUI 只调用一次，返回同一 url；
 * ⑥ 空 scene 拒绝；⑦ 生成失败 → IllegalStateException，下次调用可重试成功。
 */
class SceneBackgroundTest {

    /** 复用 ImageGenServiceTest 的假 ComfyUI 客户端（同包，直接落盘假字节 + 记录 WorkflowSpec）。 */
    private ImageGenService newService(ImageGenServiceTest.FakeComfyClient client, Path outputDir) {
        AiImageProperties props = new AiImageProperties();
        props.setOutputDir(outputDir.toString());
        props.setLoraName("pixel_art_sakuemonq_pony.safetensors");
        props.setRmbgEnabled(false);
        props.setTimeoutSeconds(30);
        return new ImageGenService(client, props);
    }

    // ── ① 生成成功：url 契约 + 落盘 + prompt 契约 ──

    @Test
    @DisplayName("B-1 生成成功：url=/ai-images/backgrounds/{hash}.png + 落盘 backgrounds/ + prompt 契约（pixel art/background/no characters/rating_safe + 负向 nsfw）")
    void generate_success() throws Exception {
        Path dir = Files.createTempDirectory("aibg-gen");
        ImageGenServiceTest.FakeComfyClient client = new ImageGenServiceTest.FakeComfyClient();
        ImageGenService svc = newService(client, dir);

        String scene = "夜晚的咖啡馆";
        String url = svc.sceneBackground(scene);

        String hash = Long.toHexString(ImageGenService.stableSeed(scene));
        assertEquals("/ai-images/backgrounds/" + hash + ".png", url, "url 契约");
        assertTrue(Files.isRegularFile(dir.resolve("backgrounds").resolve(hash + ".png")),
                "图片落盘 backgrounds/{hash}.png");

        // prompt 契约：调用 1 次文生图
        assertEquals(1, client.specs.size(), "应恰好 1 次 ComfyUI 调用");
        assertEquals(1, client.callKinds.size());
        assertEquals("txt2img", client.callKinds.get(0), "背景走文生图（非 img2img）");
        WorkflowSpec spec = client.specs.get(0);
        assertTrue(spec.positivePrompt().contains("rating_safe"), "正向含 Pony score/rating tag: " + spec.positivePrompt());
        assertTrue(spec.positivePrompt().contains("pixel art"), "正向含 pixel art");
        assertTrue(spec.positivePrompt().contains("background"), "正向含 background");
        assertTrue(spec.positivePrompt().contains("no characters"), "正向含 no characters");
        assertTrue(spec.positivePrompt().contains(scene), "正向含场景描述");
        assertTrue(spec.negativePrompt().contains("nsfw"), "负向含 nsfw 拦截");
        assertEquals(1216, spec.width(), "横构图 1216");
        assertEquals(832, spec.height(), "横构图 832");
        assertEquals(ImageGenService.stableSeed(scene), spec.seed(), "seed = 场景稳定 hash");
        // 背景图不做 RMBG（无角色）：同目录无 _t 透明版
        assertFalse(Files.list(dir.resolve("backgrounds")).anyMatch(p -> p.getFileName().toString().endsWith("_t.png")),
                "背景图不产出透明版");
    }

    // ── ② 同 scene 内存缓存 ──

    @Test
    @DisplayName("B-2 同 scene 键缓存：二次调用不触发生成，直接返回相同 url")
    void sameScene_cached() throws Exception {
        Path dir = Files.createTempDirectory("aibg-cache");
        ImageGenServiceTest.FakeComfyClient client = new ImageGenServiceTest.FakeComfyClient();
        ImageGenService svc = newService(client, dir);

        String scene = "夜晚的咖啡馆";
        String url1 = svc.sceneBackground(scene);
        String url2 = svc.sceneBackground(scene);
        String url3 = svc.sceneBackground("  夜晚的咖啡馆  "); // trim 后同键

        assertEquals(url1, url2);
        assertEquals(url1, url3, "前后空白 trim 后同键");
        assertEquals(1, client.specs.size(), "相同 scene 键只生成一次");
    }

    // ── ③ 不同 scene 不同 hash ──

    @Test
    @DisplayName("B-3 不同 scene → 不同 hash 文件与 url")
    void differentScenes_differentHash() throws Exception {
        Path dir = Files.createTempDirectory("aibg-diff");
        ImageGenServiceTest.FakeComfyClient client = new ImageGenServiceTest.FakeComfyClient();
        ImageGenService svc = newService(client, dir);

        String url1 = svc.sceneBackground("夜晚的咖啡馆");
        String url2 = svc.sceneBackground("雨中的车站");

        assertNotEquals(url1, url2, "不同场景不同 url");
        assertNotEquals(url1.substring(url1.lastIndexOf('/')), url2.substring(url2.lastIndexOf('/')));
        assertEquals(2, client.specs.size());
        assertNotEquals(client.specs.get(0).seed(), client.specs.get(1).seed(), "不同场景 seed 不同");
    }

    // ── ④ 磁盘缓存（跨重启） ──

    @Test
    @DisplayName("B-4 磁盘缓存：文件已存在（跨重启）→ 不调 ComfyUI 直接返回 url")
    void diskCache_skipsGeneration() throws Exception {
        Path dir = Files.createTempDirectory("aibg-disk");
        ImageGenServiceTest.FakeComfyClient client = new ImageGenServiceTest.FakeComfyClient();
        ImageGenService svc = newService(client, dir);

        String scene = "夜晚的咖啡馆";
        String hash = Long.toHexString(ImageGenService.stableSeed(scene));
        Path bgDir = dir.resolve("backgrounds");
        Files.createDirectories(bgDir);
        Files.write(bgDir.resolve(hash + ".png"), new byte[]{9, 9, 9});

        String url = svc.sceneBackground(scene);

        assertEquals("/ai-images/backgrounds/" + hash + ".png", url);
        assertEquals(0, client.specs.size(), "磁盘已有文件不触发生成");
        // 内存缓存随后命中
        svc.sceneBackground(scene);
        assertEquals(0, client.specs.size());
    }

    // ── ⑤ 并发同键去重 ──

    @Test
    @DisplayName("B-5 并发同键去重：多线程同时请求同 scene，ComfyUI 只调用一次，返回同一 url")
    void concurrentSameScene_singleGeneration() throws Exception {
        Path dir = Files.createTempDirectory("aibg-conc");
        ImageGenServiceTest.FakeComfyClient client = new ImageGenServiceTest.FakeComfyClient();
        ImageGenService svc = newService(client, dir);

        String scene = "夜晚的咖啡馆";
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < n; i++) tasks.add(() -> svc.sceneBackground(scene));
            List<Future<String>> futures = pool.invokeAll(tasks);
            String first = futures.get(0).get();
            for (Future<String> f : futures) {
                assertEquals(first, f.get(), "并发同键应返回同一 url");
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, client.specs.size(), "并发同键只生成一次");
    }

    // ── ⑥ 空 scene 拒绝 ──

    @Test
    @DisplayName("B-6 空/空白 scene → IllegalArgumentException")
    void blankScene_rejected() throws Exception {
        Path dir = Files.createTempDirectory("aibg-blank");
        ImageGenServiceTest.FakeComfyClient client = new ImageGenServiceTest.FakeComfyClient();
        ImageGenService svc = newService(client, dir);

        assertThrows(IllegalArgumentException.class, () -> svc.sceneBackground(null));
        assertThrows(IllegalArgumentException.class, () -> svc.sceneBackground(""));
        assertThrows(IllegalArgumentException.class, () -> svc.sceneBackground("   "));
        assertEquals(0, client.specs.size(), "空场景不触发生成");
    }

    // ── ⑦ 失败重试 ──

    @Test
    @DisplayName("B-7 生成失败 → IllegalStateException（含原因）；下次调用重试成功")
    void failure_throwsAndRetries() throws Exception {
        Path dir = Files.createTempDirectory("aibg-fail");
        ImageGenServiceTest.FakeComfyClient client = new ImageGenServiceTest.FakeComfyClient();
        client.failAtCall = 1; // 仅第 1 次调用失败
        ImageGenService svc = newService(client, dir);

        String scene = "雨中的车站";
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> svc.sceneBackground(scene));
        assertTrue(ex.getMessage().contains("生成失败"), "异常信息含原因: " + ex.getMessage());

        // 失败任务已移除 → 下次调用重试成功
        String url = svc.sceneBackground(scene);
        assertNotNull(url);
        assertTrue(url.startsWith("/ai-images/backgrounds/"));
        assertEquals(2, client.specs.size(), "首次失败 + 重试成功 = 2 次调用");
    }

    // ── ⑧ 端点契约（controller 层） ──

    @Test
    @DisplayName("B-8 POST /api/ai-image/scene-background 端点契约：{url, scene} / 缺 scene 400 / 生成失败 502")
    void controllerContract() {
        ImageGenService svc = mockService("http://x/1.png");
        ImageGenController ctrl = new ImageGenController(svc);

        // 成功路径
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("scene", "夜晚的咖啡馆");
        org.springframework.http.ResponseEntity<?> ok = ctrl.sceneBackground(body);
        assertEquals(200, ok.getStatusCode().value());
        Map<?, ?> okBody = (Map<?, ?>) ok.getBody();
        assertEquals("http://x/1.png", okBody.get("url"));
        assertEquals("夜晚的咖啡馆", okBody.get("scene"));

        // 缺 scene → 400
        org.springframework.http.ResponseEntity<?> bad = ctrl.sceneBackground(Map.of());
        assertEquals(400, bad.getStatusCode().value());

        // 生成失败 → 502
        ImageGenService thrower = mockService(null);
        ImageGenController ctrl2 = new ImageGenController(thrower);
        org.springframework.http.ResponseEntity<?> err = ctrl2.sceneBackground(Map.of("scene", "x"));
        assertEquals(502, err.getStatusCode().value());
    }

    private static ImageGenService mockService(String url) {
        return new ImageGenService(null, new AiImageProperties()) {
            @Override
            public String sceneBackground(String scene) {
                if (url == null) throw new IllegalStateException("生成失败");
                return url;
            }
        };
    }
}
