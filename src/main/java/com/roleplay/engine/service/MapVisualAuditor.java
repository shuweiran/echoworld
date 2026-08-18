package com.roleplay.engine.service;

import com.roleplay.engine.llm.MapLlmClient;
import com.roleplay.engine.simulation.map.MapContract;
import com.roleplay.engine.simulation.map.MapRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 地图视觉审核（P-0818-E 视觉审核闭环）——把生成的地图渲染成 PNG，交给小米 MiMo（mimo-v2.5
 * 多模态，已实测支持图像输入）看图审核：房间拥挤/空旷、走廊异常、大小协调、装饰密度、整体协调。
 * 输出结构化报告 {score, issues[{level,what,suggest}]}；失败降级为「无报告」，不阻断生成。
 */
@Service
public class MapVisualAuditor {

    private static final Logger log = LoggerFactory.getLogger(MapVisualAuditor.class);

    private final MapLlmClient mapLlm;
    private final int timeoutSeconds;

    @Autowired
    public MapVisualAuditor(MapLlmClient mapLlm,
                            @Value("${roleplay.map-llm.visual-audit-timeout-seconds:180}") int timeoutSeconds) {
        this.mapLlm = mapLlm;
        this.timeoutSeconds = Math.max(30, Math.min(300, timeoutSeconds));
    }

    /** 审核报告。 */
    public record AuditReport(int score, List<Map<String, Object>> issues, Map<String, Object> raw, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    /** 审核一张契约地图（渲染 → MiMo 看图 → 解析）。失败返回 error 报告（不抛异常）。 */
    public AuditReport audit(Map<String, Object> map) {
        String dataUrl = MapRenderer.renderDataUrl(map);
        if (dataUrl == null) {
            return new AuditReport(0, List.of(), Map.of(), "地图渲染失败（无审核报告）");
        }
        Map<String, Object> raw;
        try {
            // P-0818-E 实测：MiMo 视觉任务也先消耗大量 reasoning_tokens（JSON 输出易被 finish:length 截断）——
            // token 预算 4000 + 可配置超时给「推理 + 正文 JSON」留足空间；上限由服务层限制。
            raw = mapLlm.callJsonWithImage(buildPrompt(map), dataUrl, 4000, timeoutSeconds);
        } catch (Exception e) {
            log.warn("MapVisualAuditor: 审核调用异常: {}", e.getMessage());
            return new AuditReport(0, List.of(), Map.of(), "视觉审核调用失败：" + e.getMessage());
        }
        if (raw == null) {
            return new AuditReport(0, List.of(), Map.of(), "视觉审核无返回（无报告）");
        }
        return new AuditReport(scoreOf(raw), issuesOf(raw), raw, null);
    }

    /** 审核 prompt（中文，要求纯 JSON；给模型地图上下文帮助理解色块）。 */
    public static String buildPrompt(Map<String, Object> map) {
        String name = MapContract.str(map.get("name"), "未命名地图");
        int w = MapContract.intOf(map.get("width"), 0);
        int h = MapContract.intOf(map.get("height"), 0);
        int rooms = map.get("rooms") instanceof List<?> rl ? rl.size() : 0;
        int decor = map.get("decor") instanceof List<?> dl ? dl.size() : 0;
        return "这是程序生成的 RPG 地图截图（名称「" + name + "」，尺寸 " + w + "×" + h
                + " 格，房间 " + rooms + " 个，装饰 " + decor + " 个）。深色格=墙/家具/建筑（碰撞），"
                + "白色方框=房间边界，黄色圆点=搜证点，青色小格=门洞，紫色圆点=传送点。\n"
                + "请从视觉上审核布局质量，逐项判断：\n"
                + "1) 房间/建筑是否过于拥挤（房间挨太紧/走廊太窄）或过于空旷（大片无内容区域）；\n"
                + "2) 走廊/通道是否异常（断头、过窄、斜穿房间）；\n"
                + "3) 房间大小是否协调（是否有异常巨大的空区或过小房间）；\n"
                + "4) 装饰/家具密度是否合适（太少太空 / 太多太乱）；\n"
                + "5) 整体是否协调自然。\n"
                + "只输出 JSON（不要任何推理过程/前言/解释）："
                + "{\"score\":0-100整数,\"issues\":[{\"level\":\"low|medium|high\",\"what\":\"具体问题（中文）\",\"suggest\":\"改进建议（中文）\"}]}，"
                + "最多 5 条 issues；布局良好时 score≥90 且 issues 为空数组。";
    }

    private static int scoreOf(Map<String, Object> raw) {
        Object s = raw.get("score");
        if (s instanceof Number n) return Math.max(0, Math.min(100, n.intValue()));
        if (s instanceof String str) {
            try {
                return Math.max(0, Math.min(100, Integer.parseInt(str.trim())));
            } catch (NumberFormatException ignored) {
                // fallthrough
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> issuesOf(Map<String, Object> raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw.get("issues") instanceof List<?> issues)) return out;
        for (Object o : issues) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> issue = new LinkedHashMap<>();
            issue.put("level", MapContract.str(m.get("level"), "low"));
            issue.put("what", MapContract.str(m.get("what"), ""));
            issue.put("suggest", MapContract.str(m.get("suggest"), ""));
            if (!MapContract.str(m.get("what"), "").isBlank()) out.add(issue);
        }
        return out;
    }
}
