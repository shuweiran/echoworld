package com.roleplay.engine.service;

import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * P-0805-C（生图 provider 适配）：单图生成 —— 离线 SVG 占位 + provider 可配。
 */
class ImageGenerationTest {

    private ImageSpecService newService() {
        return new ImageSpecService(mock(LLMClient.class));
    }

    private Map<String, Object> unit(String kind, String name, String prompt, String aspect) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("id", "test_" + kind);
        u.put("kind", kind);
        u.put("name", name);
        u.put("prompt", prompt);
        u.put("style", "民国 noir");
        u.put("aspect", aspect);
        u.put("usage", "role_card_avatar");
        return u;
    }

    @Test
    @DisplayName("G-1 离线生成：无 provider 配置 → SVG 占位，b64 可解码为 <svg>")
    void offlineSvgGeneration() {
        ImageSpecService svc = newService();
        svc.setImageProvider("", "", ""); // 无 provider → 离线
        Map<String, Object> r = svc.generateImage(unit("character", "白司迁", "民国侦探立绘", "portrait"));

        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals("image/svg+xml", r.get("mime"));
        assertEquals(Boolean.TRUE, r.get("fallback"), "无 provider 应走占位");
        String svg = new String(Base64.getDecoder().decode((String) r.get("b64")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(svg.startsWith("<svg"), "SVG 可解码: " + svg.substring(0, 20));
        assertTrue(svg.contains("民国侦探立绘"), "SVG 含提示词");
    }

    @Test
    @DisplayName("G-2 provider 配置空判定：url 空白 → 离线占位；prompt 空回退 name")
    void providerUrlBlankFallsBack() {
        ImageSpecService svc = newService();
        svc.setImageProvider(null, null, null); // null 归一空白 → 离线
        Map<String, Object> r = svc.generateImage(unit("clue", "染血手帕", "", "square"));
        assertEquals(Boolean.TRUE, r.get("fallback"));
        assertTrue(String.valueOf(r.get("prompt")).contains("染血手帕"), "prompt 空应回退 name");
    }

    @Test
    @DisplayName("G-3 provider 失败降级：url 非空但不可达 → 捕获异常回退 SVG 占位（不抛）")
    void providerFailureFallsBack() {
        ImageSpecService svc = newService();
        // 指向不可达端口 → callImageProvider 抛异常 → 捕获回退
        svc.setImageProvider("http://127.0.0.1:1/images/generations", "sk-test", "gpt-image-1");
        Map<String, Object> r = svc.generateImage(unit("scene", "书房", "书房氛围", "landscape"));
        assertEquals(Boolean.TRUE, r.get("ok"), "provider 失败应降级不抛");
        assertEquals(Boolean.TRUE, r.get("fallback"), "应标记占位降级");
    }
}
