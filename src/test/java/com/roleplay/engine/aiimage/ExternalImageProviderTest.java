package com.roleplay.engine.aiimage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P-0820-Q：外部 OpenAI-compatible 图片模型适配，不触碰本地 ComfyUI 默认路径。 */
class ExternalImageProviderTest {
    @Test
    void openAiCompatibleProviderWritesB64Images() throws Exception {
        byte[] pngLike = new byte[]{1, 2, 3, 4};
        String b64 = Base64.getEncoder().encodeToString(pngLike);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/images/generations", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}" ).getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        Path output = Files.createTempDirectory("external-image");
        AiImageProperties props = new AiImageProperties();
        props.setProvider("openai-compatible");
        props.setExternalBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setExternalApiKey("test-key");
        props.setExternalModel("test-image-model");
        props.setOutputDir(output.toString());
        props.setRmbgEnabled(false);
        ComfyUIClient comfy = new ComfyUIClient(new ObjectMapper(), "http://127.0.0.1:1", 1, 10);
        ImageGenService service = new ImageGenService(comfy, props);
        try {
            service.registerCharacter("external", "外部角色", "short hair", "pixel art");
            ImageGenService.GenTask task = service.triggerGenerate("external");
            long deadline = System.currentTimeMillis() + 5000;
            while (task.status() == ImageGenService.GenTask.Status.RUNNING && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(ImageGenService.GenTask.Status.DONE, task.status());
            assertTrue(requests.get() >= 8);
            assertTrue(Files.isRegularFile(output.resolve("external/avatar.png")));
            assertEquals(4, Files.readAllBytes(output.resolve("external/avatar.png")).length);
        } finally {
            service.shutdown();
            server.stop(0);
        }
    }
}
