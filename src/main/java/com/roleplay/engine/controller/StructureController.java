package com.roleplay.engine.controller;

import com.roleplay.engine.service.StructureMapService;
import com.roleplay.engine.simulation.structure.StructureContract;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 大型结构生成 API（docs/结构树契约与生成API设计.md §4）——POST /api/structure/generate。
 *
 * <p>请求：theme（必填）/ kind（castle|mansion|city_block|dungeon|custom，缺省 custom）/
 * seed（缺省当前毫秒）/ width·height（单图预算覆盖，超 256 clamp）/ map_mode（single|multi）/
 * style / locations / clue_locations。
 *
 * <p>错误 400：缺 theme / kind 未知 / 尺寸非法；生成失败（开关关闭 / custom LLM 蓝图 P4 /
 * 模板缺失 / 结构非法 / 布局失败）→ BSP 兜底（fallback 记录原因），不 500。
 */
@RestController
@RequestMapping("/api/structure")
public class StructureController {

    private final StructureMapService structureMapService;

    public StructureController(StructureMapService structureMapService) {
        this.structureMapService = structureMapService;
    }

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "请求体不能为空（缺少 theme）"));
        }
        String theme = StructureContract.str(body.get("theme"), "").trim();
        if (theme.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 theme"));
        }
        String kind = StructureContract.str(body.get("kind"), "custom").trim().toLowerCase(Locale.ROOT);
        if (!StructureContract.KNOWN_KINDS.contains(kind)) {
            return ResponseEntity.badRequest().body(Map.of("error", "未知 kind：" + kind
                    + "（支持 castle/mansion/city_block/dungeon/custom）"));
        }
        long seed = parseLong(body.get("seed"), 0);
        final int width;
        final int height;
        try {
            width = parseInt(body.get("width"), 0);
            height = parseInt(body.get("height"), 0);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        if (width < 0 || height < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "width/height 必须为正整数"));
        }
        String mapMode = StructureContract.str(body.get("map_mode"), "single");
        if (!List.of("single", "multi", "exterior").contains(mapMode.trim().toLowerCase(Locale.ROOT))) {
            return ResponseEntity.badRequest().body(Map.of("error", "map_mode 必须为 single/multi/exterior"));
        }
        String style = StructureContract.str(body.get("style"), "");
        List<String> locations = stringList(body.get("locations"));
        List<String> clueLocations = stringList(body.get("clue_locations"));
        // P-0818-E：视觉审核开关（audit=true → 渲染截图 + 小米 MiMo 看图审核 + 自动修正）
        boolean audit = Boolean.TRUE.equals(body.get("audit"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("audit")));

        try {
            return ResponseEntity.ok(structureMapService.generate(
                    new StructureMapService.GenerateRequest(theme, kind, seed, width, height,
                            mapMode, style, locations, clueLocations, audit)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static long parseLong(Object o, long def) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    private static int parseInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            if (s.isBlank()) return def;
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("尺寸必须为整数");
            }
        }
        if (o != null) throw new IllegalArgumentException("尺寸必须为整数");
        return def;
    }

    private static List<String> stringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object v : l) {
                if (v != null) out.add(String.valueOf(v));
            }
        }
        return out;
    }
}
