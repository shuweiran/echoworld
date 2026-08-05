package com.roleplay.engine.service;

import com.roleplay.engine.llm.LLMClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P-0805-A（生图接入，后端）：image_spec 合成器 + 可选 LLM 扩写。
 *
 * <p>对齐独立 demo（static/simulation/imagegen/）的契约 v1：
 * 「结构化生图描述（image_spec）→ 生图 provider → 渲染」管线中，本服务负责产出
 * <b>结构化描述</b>（文本模型擅长的部分），扩散模型/程序化占位由外部 provider 负责（本服务不调图）。
 *
 * <p>由剧本 schema v1 派生四种生图单元：
 * <ul>
 *   <li>character → 角色立绘（role_card_avatar）</li>
 *   <li>background/locations → 场景氛围图（scene_background）</li>
 *   <li>clues → 线索物证图（clue_evidence）</li>
 *   <li>theme → 瓦片风格锚点（tileset_style，实际瓦片仍走程序化 BSP，AI 只出概念风格）</li>
 * </ul>
 *
 * <p>风格统一 &gt; 单图质量：全局风格锚点由主题派生（styleForTheme），所有单元继承同一风格串，
 * 供 provider 用固定模板 + 图生图统一风格。
 */
@Service
public class ImageSpecService {

    private static final Logger log = LoggerFactory.getLogger(ImageSpecService.class);

    /** 主题 → 全局风格锚点（子串匹配首个命中）。 */
    static final List<Map.Entry<List<String>, String>> THEME_STYLES = List.of(
        Map.entry(List.of("民国", "宅邸", "庄园", "老宅"), "民国 noir 复古手绘，低饱和，胶片颗粒，油画笔触"),
        Map.entry(List.of("古风", "仙侠", "江湖", "宫廷", "武侠"), "新国风水墨，工笔线稿，宣纸质感，淡彩"),
        Map.entry(List.of("科幻", "未来", "赛博", "太空", "星际"), "赛博霓虹，硬边光效，冷色调，高对比"),
        Map.entry(List.of("校园", "青春", "教室", "高中"), "清新动漫风，明亮柔光，日系厚涂"),
        Map.entry(List.of("恐怖", "怪谈", "诡秘", "惊悚"), "暗黑哥特，低光高对比，雾感，电影感"),
        Map.entry(List.of("童话", "奇幻", "魔法", "森林"), "手绘绘本风，暖色，圆润造型，细节丰富")
    );
    static final String DEFAULT_STYLE = "商业插画风，电影级打光，细节丰富";

    private final LLMClient llmClient;

    public ImageSpecService(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    /** 主题 → 风格锚点。 */
    public static String styleForTheme(String theme) {
        if (theme == null || theme.isBlank()) return DEFAULT_STYLE;
        for (Map.Entry<List<String>, String> s : THEME_STYLES) {
            for (String k : s.getKey()) {
                if (theme.contains(k)) return s.getValue();
            }
        }
        return DEFAULT_STYLE;
    }

    /**
     * 由剧本 schema v1 合成 image_spec（契约 v1）。
     *
     * @param script 剧本 schema（可 null/空 —— 仅主题驱动，产出场景+瓦片风格；demo 与旧调用零依赖）
     * @param theme  主题（空则从 script 推导）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> synthesize(Map<String, Object> script, String theme) {
        String effTheme = (theme != null && !theme.isBlank())
                ? theme
                : (script != null && str(script.get("theme")) != null && !str(script.get("theme")).isBlank()
                        ? str(script.get("theme"))
                        : (script != null && meta(script, "title") != null && !meta(script, "title").isBlank()
                                ? meta(script, "title")
                                : "未命名剧本"));
        String style = styleForTheme(effTheme);

        List<Map<String, Object>> images = new ArrayList<>();
        List<Map<String, Object>> roles = script == null ? List.of() : list(script.get("roles"));
        // 角色 → 立绘
        for (int i = 0; i < roles.size(); i++) {
            Map<String, Object> r = roles.get(i);
            String name = str(r.get("name"));
            if (name.isBlank()) name = "角色" + (i + 1);
            images.add(unit(
                    "char_" + str(r.get("id")) + "_" + i,
                    "character", name,
                    pick(r.get("intro"), r.get("secret"), null),
                    style, "portrait", "role_card_avatar", name));
        }
        // 背景 → 主场景
        String bg = script == null ? "" : str(script.get("background"));
        if (!bg.isBlank()) {
            images.add(unit("scene_main", "scene", "主场景", bg, style,
                    "landscape", "scene_background", effTheme));
        }
        // locations → 房间氛围图
        List<Map<String, Object>> locations = script == null ? List.of() : list(script.get("locations"));
        for (int i = 0; i < locations.size(); i++) {
            String loc = str(locations.get(i));
            images.add(unit("scene_" + (i + 1), "scene", loc, loc + " 的环境细节，符合主题氛围",
                    style, "landscape", "scene_background", loc));
        }
        // clues → 物证图
        List<Map<String, Object>> clues = script == null ? List.of() : list(script.get("clues"));
        for (int i = 0; i < clues.size(); i++) {
            Map<String, Object> c = clues.get(i);
            String title = str(c.get("title"));
            if (title.isBlank()) title = "线索" + (i + 1);
            images.add(unit(
                    "clue_" + str(c.get("id")) + "_" + i,
                    "clue", title, str(c.get("content")),
                    style, "square", "clue_evidence", str(c.get("location"))));
        }
        // 瓦片风格锚点
        images.add(unit("tile_style", "tile_style", effTheme + " 瓦片风格",
                "2D 俯视 tilemap 瓦片图集，32px，俯视，可无缝平铺，风格统一：" + style,
                style, "square", "tileset_style", effTheme));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("image_version", 1);
        out.put("theme", effTheme);
        out.put("style", style);
        out.put("images", images);
        return out;
    }

    /** 单个生图单元（契约 v1 字段）。 */
    private static Map<String, Object> unit(String id, String kind, String name, String prompt,
                                            String style, String aspect, String usage, String related) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        m.put("name", name);
        m.put("prompt", prompt == null ? "" : prompt);
        m.put("negative", "文字水印, 低质量, 变形");
        m.put("style", style);
        m.put("aspect", aspect);
        m.put("usage", usage);
        m.put("related", related == null ? "" : related);
        m.put("status", "pending");
        return m;
    }

    // ═══════════════════════════════════════════════════════════
    //  P-0805-C（生图 provider 适配）：单图生成 —— 真实 API 可配 / 离线 SVG 占位
    // ═══════════════════════════════════════════════════════════

    /** OpenAI 兼容 /images/generations 端点（DALL-E / 通义万相 / 即梦若走兼容接口可接）；空 = 走离线 SVG 占位。 */
    @Value("${roleplay.image.provider-url:}")
    private String imageProviderUrl = "";

    /** OpenAI 兼容生图 API Key（provider-url 非空时必填）。 */
    @Value("${roleplay.image.provider-key:}")
    private String imageProviderKey = "";

    /** 生图模型（DALL-E 等；空 = 默认 gpt-image-1）。 */
    @Value("${roleplay.image.model:}")
    private String imageProviderModel = "";

    /** P-0805-C（测试钩子）：运行时切换生图 provider 配置。 */
    public void setImageProvider(String url, String key, String model) {
        this.imageProviderUrl = url == null ? "" : url;
        this.imageProviderKey = key == null ? "" : key;
        this.imageProviderModel = model == null ? "" : model;
    }

    /**
     * P-0805-C：单图生成 —— 三层：① 真实 provider（OpenAI 兼容，b64_json）② 离线 SVG 占位。
     *
     * @param unit 生图单元（契约 v1，含 prompt/style/aspect）
     * @return {ok, mime, b64, fallback, prompt}；b64 为图片 base64（不含 data: 前缀）
     */
    public Map<String, Object> generateImage(Map<String, Object> unit) {
        String prompt = unit.get("prompt") instanceof String s && !s.isBlank() ? s : String.valueOf(unit.get("name"));
        String style = unit.get("style") instanceof String st && !st.isBlank() ? st : DEFAULT_STYLE;
        String fullPrompt = prompt + "，风格：" + style;
        String aspect = unit.get("aspect") instanceof String a ? a : "square";

        // ① 真实 provider
        if (!imageProviderUrl.isBlank()) {
            try {
                return callImageProvider(fullPrompt, aspect);
            } catch (Exception e) {
                log.warn("Image generation provider failed, falling back to SVG placeholder: {}", e.getMessage());
            }
        }
        // ② 离线 SVG 占位
        return Map.of("ok", true, "mime", "image/svg+xml", "b64", offlineSvgB64(fullPrompt, aspect),
                "fallback", true, "prompt", fullPrompt);
    }

    /** OpenAI 兼容 /images/generations 调用（response_format=b64_json）。 */
    private Map<String, Object> callImageProvider(String prompt, String aspect) throws Exception {
        String size = switch (aspect) {
            case "portrait" -> "1024x1536";
            case "landscape" -> "1536x1024";
            default -> "1024x1024";
        };
        String bodyJson = """
            {"model":"%s","prompt":%s,"n":1,"size":"%s","response_format":"b64_json"}
            """.formatted(imageProviderModel.isBlank() ? "gpt-image-1" : imageProviderModel,
                quote(prompt), size);
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(imageProviderUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + imageProviderKey)
                .timeout(java.time.Duration.ofSeconds(60))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();
        java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build()
                .send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("provider HTTP " + resp.statusCode());
        }
        String json = resp.body();
        // 解析 data[0].b64_json
        int idx = json.indexOf("\"b64_json\":\"");
        if (idx >= 0) {
            int start = idx + "\"b64_json\":\"".length();
            int end = json.indexOf('"', start);
            if (end > start) {
                return Map.of("ok", true, "mime", "image/png", "b64", json.substring(start, end),
                        "fallback", false, "prompt", prompt);
            }
        }
        throw new IllegalStateException("provider 响应无 b64_json");
    }

    /** JSON 字符串转义（prompt 注入 body 用）。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper SPEC_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private static String quote(String s) {
        return SPEC_MAPPER.valueToTree(s).toString();
    }

    /** 离线 SVG 占位 → base64（对齐 demo providers.js 语义：kind 配色 + 标题 + 提示词）。 */
    static String offlineSvgB64(String prompt, String aspect) {
        int w = aspect.equals("portrait") ? 400 : aspect.equals("landscape") ? 640 : 400;
        int h = aspect.equals("portrait") ? 600 : aspect.equals("landscape") ? 360 : 400;
        String desc = prompt == null ? "" : (prompt.length() > 60 ? prompt.substring(0, 60) : prompt);
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + w + "\" height=\"" + h
                + "\" viewBox=\"0 0 " + w + " " + h + "\">"
                + "<defs><linearGradient id=\"g1\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\">"
                + "<stop offset=\"0\" stop-color=\"#7c3aed\"/><stop offset=\"1\" stop-color=\"#2563eb\"/>"
                + "</linearGradient></defs>"
                + "<rect width=\"" + w + "\" height=\"" + h + "\" fill=\"url(#g1)\"/>"
                + "<rect x=\"8\" y=\"8\" width=\"" + (w - 16) + "\" height=\"" + (h - 16)
                + "\" fill=\"none\" stroke=\"rgba(255,255,255,0.55)\" stroke-width=\"2\" rx=\"12\"/>"
                + "<text x=\"" + (w / 2) + "\" y=\"" + (h / 2 - 20) + "\" text-anchor=\"middle\" font-size=\"26\" "
                + "font-family=\"sans-serif\">AI 生成占位</text>"
                + "<text x=\"16\" y=\"" + (h - 16) + "\" font-size=\"11\" fill=\"rgba(255,255,255,0.85)\" "
                + "font-family=\"sans-serif\">" + esc(desc) + "</text></svg>";
        return java.util.Base64.getEncoder().encodeToString(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ── 工具 ───────────────────────────────────────────

    private static String pick(Object... vals) {
        for (Object v : vals) {
            String s = str(v);
            if (!s.isBlank()) return s;
        }
        return "";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String meta(Map<String, Object> script, String key) {
        Object m = script.get("metadata");
        return m instanceof Map<?, ?> mm && mm.get(key) != null ? String.valueOf(mm.get(key)) : "";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object x : l) {
            if (x instanceof Map<?, ?> mm) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : mm.entrySet()) {
                    if (e.getKey() != null) m.put(String.valueOf(e.getKey()), e.getValue());
                }
                out.add(m);
            }
        }
        return out;
    }
}
