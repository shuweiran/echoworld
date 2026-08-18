package com.roleplay.engine.service;

import com.roleplay.engine.simulation.map.BspMapGenerator;
import com.roleplay.engine.simulation.map.MapExits;
import com.roleplay.engine.simulation.map.MapValidator;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.structure.StructureContract;
import com.roleplay.engine.simulation.structure.StructureLayoutGenerator;
import com.roleplay.engine.simulation.structure.StructureLlmBlueprint;
import com.roleplay.engine.simulation.structure.StructureTemplates;
import com.roleplay.engine.simulation.structure.StructureValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 大型结构生成统一入口（docs/结构树契约与生成API设计.md §4）——POST /api/structure/generate 服务层。
 *
 * <p>管线：L0 结构模板（kind 模板库，零成本）/ LLM 语义蓝图（kind=custom，P4）→
 * 校验（StructureValidator）→ L1 布局
 * （StructureLayoutGenerator：单图优先 / 超限拆多图 + warps）→ L2-L4 复用（线索绑定 /
 * 出口推导 / 契约 v1 输出，Phaser 直接渲染）。
 *
 * <p>失败策略：开关关闭 / custom 未启用 LLM（l0-source=template）/ 模板缺失 / LLM 蓝图失败 /
 * 结构树非法 /
 * 布局校验失败 → BSP 常规地图兜底（fallback 记录原因），不 500（对齐 ScriptMapService 降级语义）。
 */
@Service
public class StructureMapService {

    private static final Logger log = LoggerFactory.getLogger(StructureMapService.class);

    /** P-0818-B/E 修正：结构生成回主链路 DeepSeek（快）；视觉审核用 MapVisualAuditor（内部小米 MiMo 多模态） */
    private final LLMClient llmClient;
    private final MapVisualAuditor auditor;
    private final boolean enabled;
    private final String l0Source;
    private final int maxSingleMapWidth;
    private final int maxSingleMapHeight;

    @Autowired
    public StructureMapService(
            LLMClient llmClient, MapVisualAuditor auditor,
            @Value("${roleplay.structure.enabled:true}") boolean enabled,
            @Value("${roleplay.structure.l0-source:template}") String l0Source,
            @Value("${roleplay.structure.max-single-map-width:128}") int maxSingleMapWidth,
            @Value("${roleplay.structure.max-single-map-height:128}") int maxSingleMapHeight) {
        this.llmClient = llmClient;
        this.auditor = auditor;
        this.enabled = enabled;
        this.l0Source = l0Source;
        this.maxSingleMapWidth = Math.max(16, maxSingleMapWidth);
        this.maxSingleMapHeight = Math.max(16, maxSingleMapHeight);
    }

    /** 测试直构构造（无 LLM；custom 恒走 BSP 兜底，等价 l0-source=template）。 */
    public StructureMapService(boolean enabled, String l0Source, int maxSingleMapWidth, int maxSingleMapHeight) {
        this(null, null, enabled, l0Source, maxSingleMapWidth, maxSingleMapHeight);
    }

    /** 生成请求（控制器解析后传入）。 */
    public record GenerateRequest(String theme, String kind, long seed, int width, int height,
                                  String mapMode, String style,
                                  List<String> locations, List<String> clueLocations, boolean audit) {
    }

    /**
     * 生成结构：{structure, maps{map_id→契约v1}, current_map_id, connections[],
     * generator{kind,seed,map_mode,validation}, fallback[]}。
     *
     * @throws IllegalArgumentException kind 未知（控制器转 400）
     */
    public Map<String, Object> generate(GenerateRequest req) {
        List<String> fallback = new ArrayList<>();
        String kind = req.kind() == null || req.kind().isBlank()
                ? "custom" : req.kind().trim().toLowerCase(Locale.ROOT);
        if (!StructureContract.KNOWN_KINDS.contains(kind)) {
            throw new IllegalArgumentException("未知 kind：" + kind
                    + "（支持 castle/mansion/city_block/dungeon/custom）");
        }
        String requestedMode = req.mapMode() == null || req.mapMode().isBlank()
                ? "single" : req.mapMode().trim().toLowerCase(Locale.ROOT);
        if (!List.of("single", "multi", "exterior").contains(requestedMode)) {
            throw new IllegalArgumentException("map_mode 必须为 single/multi/exterior");
        }
        long seed = req.seed() <= 0 ? System.currentTimeMillis() : req.seed();
        // 单图预算：请求 width/height 覆盖配置；超契约 max 256×256 clamp（P-0817-D 上限）
        int budgetW = req.width() > 0 ? Math.min(req.width(), 256) : maxSingleMapWidth;
        int budgetH = req.height() > 0 ? Math.min(req.height(), 256) : maxSingleMapHeight;
        boolean forceMulti = "multi".equals(requestedMode);
        boolean exterior = "exterior".equals(requestedMode);

        if (!enabled) {
            fallback.add("roleplay.structure.enabled=false → BSP 兜底");
            return fallbackResponse(req, kind, seed, fallback);
        }

        // L0：结构模板库（已知 kind，零成本）/ LLM 语义蓝图（kind=custom，P4）
        Map<String, Object> structure;
        String l0Used;
        if ("custom".equals(kind)) {
            if ("template".equalsIgnoreCase(l0Source)) {
                fallback.add("kind=custom 需要 LLM 蓝图 L0（l0-source=template 未启用 LLM）→ BSP 兜底");
                return fallbackResponse(req, kind, seed, fallback);
            }
            structure = StructureLlmBlueprint.blueprint(llmClient, req.theme(), req.style(), seed);
            if (structure == null) {
                fallback.add("LLM 蓝图 L0 生成失败（kind=custom，超时/空输出）→ BSP 兜底");
                return fallbackResponse(req, kind, seed, fallback);
            }
            StructureValidator.Result sv = StructureValidator.validate(structure);
            if (!sv.ok()) {
                fallback.add("LLM 蓝图结构树校验失败（" + String.join("；", sv.errors()) + "）→ BSP 兜底");
                return fallbackResponse(req, kind, seed, fallback);
            }
            l0Used = "llm";
        } else {
            structure = StructureTemplates.template(kind);
            if (structure == null) {
                fallback.add("结构模板缺失（kind=" + kind + "）→ BSP 兜底");
                return fallbackResponse(req, kind, seed, fallback);
            }
            structure = StructureContract.normalize(structure);
            structure.put("seed", seed);
            if (req.theme() != null && !req.theme().isBlank()) {
                structure.put("name", req.theme().trim());
            }
            StructureValidator.Result sv = StructureValidator.validate(structure);
            if (!sv.ok()) {
                fallback.add("结构树校验失败（" + String.join("；", sv.errors()) + "）→ BSP 兜底");
                return fallbackResponse(req, kind, seed, fallback);
            }
            l0Used = "template".equalsIgnoreCase(l0Source) ? "template" : l0Source;
        }

        // L1：布局（单图优先 / 超限拆多图 + warps）
        StructureLayoutGenerator.Result layout;
        try {
            layout = StructureLayoutGenerator.layout(structure, seed, budgetW, budgetH, forceMulti, exterior,
                    req.locations(), req.clueLocations());
        } catch (Exception e) {
            log.warn("StructureMapService: L1 layout failed: {}", e.getMessage());
            fallback.add("L1 布局失败（" + e.getMessage() + "）→ BSP 兜底");
            return fallbackResponse(req, kind, seed, fallback);
        }

        // 校验：结构级 + 每图契约级（MapValidator）+ 多图 warps 级
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : layout.maps().entrySet()) {
            MapValidator.Result mv = MapValidator.validateMap(e.getValue());
            errors.addAll(mv.errors());
            warnings.addAll(mv.warnings());
            StructureValidator.Result svm = StructureValidator.validateMap(e.getValue());
            errors.addAll(svm.errors());
            warnings.addAll(svm.warnings());
        }
        StructureValidator.Result sw = StructureValidator.validateWarps(layout.maps());
        errors.addAll(sw.errors());
        warnings.addAll(sw.warnings());
        if (!errors.isEmpty()) {
            fallback.add("结构生成校验失败（" + String.join("；", errors) + "）→ BSP 兜底");
            return fallbackResponse(req, kind, seed, fallback);
        }

        // P-0818-E（视觉审核闭环）：audit=true → 渲染截图 → 小米 MiMo 看图审核 → 按问题程序化修正
        // （拥挤/走廊窄 → 房间间距 +1；空旷/太空 → 间距 -1）→ 重生成 → 再审核（最多 1 轮修正）
        Map<String, Object> auditResp = null;
        if (req.audit() && auditor != null) {
            Map<String, Object> current = layout.maps().get(layout.maps().keySet().iterator().next());
            MapVisualAuditor.AuditReport r1 = auditor.audit(current);
            List<Map<String, Object>> rounds = new ArrayList<>();
            rounds.add(roundEntry(1, r1));
            int finalScore = r1.score();
            List<Map<String, Object>> finalIssues = r1.issues();
            String auditError = r1.error();
            Map<String, Object> tweaks = new LinkedHashMap<>();
            int gutter = StructureLayoutGenerator.GUTTER;
            if (r1.ok() && (r1.score() < 85 || !r1.issues().isEmpty())) {
                int delta = gutterDelta(r1.issues(), r1.score());
                if (delta != 0) {
                    int newGutter = Math.max(1, Math.min(5, gutter + delta));
                    if (newGutter != gutter) {
                        try {
                            StructureLayoutGenerator.Result re = StructureLayoutGenerator.layout(
                                    structure, seed, budgetW, budgetH, forceMulti, exterior, newGutter,
                                    req.locations(), req.clueLocations());
                            List<String> reErrors = new ArrayList<>();
                            for (Map.Entry<String, Map<String, Object>> e : re.maps().entrySet()) {
                                reErrors.addAll(MapValidator.validateMap(e.getValue()).errors());
                                reErrors.addAll(StructureValidator.validateMap(e.getValue()).errors());
                            }
                            reErrors.addAll(StructureValidator.validateWarps(re.maps()).errors());
                            if (reErrors.isEmpty()) {
                                layout = re;
                                tweaks.put("gutter", newGutter);
                                Map<String, Object> current2 =
                                        layout.maps().get(layout.maps().keySet().iterator().next());
                                MapVisualAuditor.AuditReport r2 = auditor.audit(current2);
                                rounds.add(roundEntry(2, r2));
                                if (r2.ok()) {
                                    finalScore = r2.score();
                                    finalIssues = r2.issues();
                                    auditError = null;
                                } else {
                                    auditError = r2.error();
                                }
                            }
                        } catch (Exception e2) {
                            log.warn("StructureMapService: 视觉审核修正重生成失败: {}", e2.getMessage());
                        }
                    }
                }
            }
            auditResp = new LinkedHashMap<>();
            auditResp.put("score", finalScore);
            auditResp.put("issues", finalIssues);
            auditResp.put("rounds", rounds);
            auditResp.put("tweaks", tweaks);
            if (auditError != null) auditResp.put("error", auditError);
        }

        Map<String, Object> generator = new LinkedHashMap<>();
        generator.put("l0", l0Used);
        generator.put("kind", kind);
        generator.put("seed", seed);
        generator.put("map_mode", exterior ? "exterior"
                : layout.multi() ? "multi" : "single");
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("ok", true);
        validation.put("errors", List.of());
        validation.put("warnings", warnings);
        generator.put("validation", validation);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("structure", structure);
        resp.put("maps", layout.maps());
        resp.put("current_map_id", layout.maps().keySet().iterator().next());
        resp.put("connections", layout.connections());
        resp.put("exteriors", layout.exteriors());
        resp.put("generator", generator);
        resp.put("fallback", fallback);
        if (auditResp != null) resp.put("audit", auditResp);
        return resp;
    }

    /** 审核问题 → 房间间距修正量（拥挤/走廊窄 → +1；空旷/太空 → -1；无关键词且分数低 → 保守 +1）。 */
    private static int gutterDelta(List<Map<String, Object>> issues, int score) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> i : issues) {
            sb.append(i.get("what")).append(' ').append(i.get("suggest")).append(' ');
        }
        String text = sb.toString();
        if (text.contains("拥挤") || text.contains("太挤") || text.contains("过密")
                || text.contains("过窄") || text.contains("挨太近") || text.contains("走廊")
                || text.contains("太近") || text.contains("紧")) {
            return 1;
        }
        if (text.contains("空旷") || text.contains("太空") || text.contains("空区")
                || text.contains("松散") || text.contains("过大")) {
            return -1;
        }
        return score >= 0 && score < 60 ? 1 : 0;
    }

    private static Map<String, Object> roundEntry(int round, MapVisualAuditor.AuditReport r) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("round", round);
        entry.put("score", r.score());
        entry.put("issues", r.issues());
        if (!r.ok()) entry.put("error", r.error());
        return entry;
    }

    /** BSP 兜底响应（单图；fallback 记录原因；不 500）。 */
    private Map<String, Object> fallbackResponse(GenerateRequest req, String kind, long seed,
                                                 List<String> fallback) {
        Map<String, Object> bsp = BspMapGenerator.generate(BspMapGenerator.Options.of(seed, 64, 48, -1));
        Map<String, Object> covered = bsp;
        if (req.clueLocations() != null && !req.clueLocations().isEmpty()) {
            covered = ScriptMapService.ensureClueZoneCoverage(
                    ScriptMapService.bindClueLocations(bsp, req.clueLocations()), req.clueLocations());
        }
        covered.put("exits", MapExits.deriveExits(covered));

        Map<String, Object> maps = new LinkedHashMap<>();
        maps.put("map_1", covered);
        Map<String, Object> generator = new LinkedHashMap<>();
        generator.put("l0", "bsp");
        generator.put("kind", kind);
        generator.put("seed", seed);
        generator.put("map_mode", "single");
        MapValidator.Result v = MapValidator.validateMap(covered);
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("ok", v.ok());
        validation.put("errors", v.errors());
        validation.put("warnings", v.warnings());
        generator.put("validation", validation);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("structure", null);
        resp.put("maps", maps);
        resp.put("current_map_id", "map_1");
        resp.put("connections", List.of());
        resp.put("generator", generator);
        resp.put("fallback", fallback);
        return resp;
    }
}
