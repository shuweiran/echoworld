package com.roleplay.engine.service;

import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * P-0805-A（生图接入，后端）：ImageSpecService 契约 v1 验收。
 *
 * <p>对齐独立 demo（imagegen）：
 * ① 由剧本 schema v1 合成四类生图单元（角色立绘/场景/物证/瓦片风格）
 * ② 全局风格锚点按主题派生（风格统一 > 单图质量）
 * ③ 宽容输入（script 为空 / theme 为空）不崩
 */
class ImageSpecServiceTest {

    private ImageSpecService newService() {
        return new ImageSpecService(mock(LLMClient.class));
    }

    private Map<String, Object> v1Script() {
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("schema_version", 1);
        script.put("theme", "民国宅邸凶案");
        script.put("metadata", Map.of("title", "民国宅邸凶案"));
        script.put("background", "民国二十年的秋夜，雨雾笼罩的沈家大宅。家宴散后，老爷沈万堂死于书房。");
        script.put("roles", List.of(
            Map.of("id", "role_1", "name", "白司迁", "intro", "留洋归来的私家侦探", "secret", "我在书房发现一封信"),
            Map.of("id", "role_2", "name", "沈夫人", "intro", "续弦", "secret", "我在花园见过争执")));
        script.put("locations", List.of("书房", "客厅"));
        script.put("clues", List.of(
            Map.of("id", "c1", "title", "未喝完的龙井", "location", "书房", "content", "残茶有苦杏仁味"),
            Map.of("id", "c2", "title", "染血的手帕", "location", "花园", "content", "绣着青字")));
        return script;
    }

    @Test
    @DisplayName("S-1 由剧本合成：image_version=1 + 四类单元齐备")
    void synthesizeFourKinds() {
        ImageSpecService svc = newService();
        Map<String, Object> spec = svc.synthesize(v1Script(), null);

        assertEquals(1, spec.get("image_version"));
        assertEquals("民国宅邸凶案", spec.get("theme"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> images = (List<Map<String, Object>>) spec.get("images");
        assertTrue(images.size() >= 1);
        assertTrue(images.stream().anyMatch(i -> "character".equals(i.get("kind"))), "应含角色立绘");
        assertTrue(images.stream().anyMatch(i -> "scene".equals(i.get("kind"))), "应含场景图");
        assertTrue(images.stream().anyMatch(i -> "clue".equals(i.get("kind"))), "应含物证图");
        assertTrue(images.stream().anyMatch(i -> "tile_style".equals(i.get("kind"))), "应含瓦片风格锚点");
    }

    @Test
    @DisplayName("S-2 单元字段齐备：id/kind/name/prompt/negative/style/aspect/usage/related/status")
    void unitFieldsComplete() {
        ImageSpecService svc = newService();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> images =
            (List<Map<String, Object>>) svc.synthesize(v1Script(), null).get("images");
        for (Map<String, Object> u : images) {
            assertNotNull(u.get("id"), "缺 id");
            assertNotNull(u.get("kind"));
            assertNotNull(u.get("name"));
            assertNotNull(u.get("prompt"));
            assertNotNull(u.get("negative"));
            assertNotNull(u.get("style"));
            assertNotNull(u.get("aspect"));
            assertNotNull(u.get("usage"));
            assertNotNull(u.get("related"));
            assertEquals("pending", u.get("status"));
        }
    }

    @Test
    @DisplayName("S-3 全局风格锚点：民国→民国 noir；未知主题→默认；全部单元继承同一风格")
    void styleAnchorUnified() {
        ImageSpecService svc = newService();
        Map<String, Object> spec = svc.synthesize(v1Script(), null);
        String style = (String) spec.get("style");
        assertTrue(style.contains("民国"), "民国主题应派生民国风格: " + style);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> images = (List<Map<String, Object>>) spec.get("images");
        assertTrue(images.stream().allMatch(i -> style.equals(i.get("style"))), "所有单元应继承同一风格锚点");

        assertTrue(ImageSpecService.styleForTheme("赛博公寓").contains("赛博"));
        assertTrue(ImageSpecService.styleForTheme("随便什么").length() > 0);
    }

    @Test
    @DisplayName("S-4 宽容输入：script 为空（纯主题驱动）不崩；theme 为空回退剧本标题")
    void tolerantInputs() {
        ImageSpecService svc = newService();
        // 纯主题（无剧本）
        Map<String, Object> spec1 = svc.synthesize(null, "科幻凶案");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> imgs1 = (List<Map<String, Object>>) spec1.get("images");
        assertFalse(imgs1.isEmpty(), "纯主题也应产出（场景+瓦片）");
        // theme 为空 → 回退剧本标题
        Map<String, Object> spec2 = svc.synthesize(v1Script(), "");
        assertEquals("民国宅邸凶案", spec2.get("theme"), "theme 空应回退剧本标题");
        // 双空 → 不崩，默认主题
        Map<String, Object> spec3 = svc.synthesize(null, "");
        assertNotNull(spec3.get("theme"));
        assertFalse(((List<?>) spec3.get("images")).isEmpty());
    }

    @Test
    @DisplayName("S-5 角色/线索数量映射：2 角色→2 立绘，2 线索→2 物证")
    void countMapping() {
        ImageSpecService svc = newService();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> images =
            (List<Map<String, Object>>) svc.synthesize(v1Script(), null).get("images");
        assertEquals(2, images.stream().filter(i -> "character".equals(i.get("kind"))).count());
        assertEquals(2, images.stream().filter(i -> "clue".equals(i.get("kind"))).count());
        assertEquals(1, images.stream().filter(i -> "tile_style".equals(i.get("kind"))).count());
    }
}
