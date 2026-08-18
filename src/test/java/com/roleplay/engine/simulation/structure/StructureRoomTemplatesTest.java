package com.roleplay.engine.simulation.structure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-0817-N（L2 房间内容）：StructureRoomTemplates 配方库完整性。
 * 覆盖：四套结构模板的全部模板键都有专属配方（非默认）、每配方含搜证锚点、家具尺寸合法、未知键默认兜底。
 */
class StructureRoomTemplatesTest {

    @Test
    @DisplayName("四套模板的全部房间模板键都有专属配方（含搜证锚点 note）")
    void allTemplateKeysHaveRecipes() {
        for (String kind : List.of("castle", "mansion", "city_block", "dungeon")) {
            Map<String, Object> structure = StructureContract.normalize(StructureTemplates.template(kind));
            Set<String> keys = new LinkedHashSet<>();
            for (Map<String, Object> n : StructureContract.leafNodes(structure)) {
                String t = StructureContract.str(n.get("template"), "");
                if (!t.isBlank()) keys.add(t);
            }
            assertFalse(keys.isEmpty(), kind + " 应有模板键");
            for (String key : keys) {
                assertTrue(StructureRoomTemplates.hasRecipe(key),
                        kind + " 模板键 " + key + " 缺少专属配方");
                StructureRoomTemplates.Recipe r = StructureRoomTemplates.recipe(key);
                assertNotSame(StructureRoomTemplates.defaultRecipe(), r,
                        kind + " 模板键 " + key + " 落到默认配方");
                assertNotNull(r.noteTarget(), kind + " 配方 " + key + " 缺少搜证锚点 noteTarget");
                for (StructureRoomTemplates.Furniture f : r.furniture()) {
                    int[] sz = StructureRoomTemplates.FURNITURE_SIZES.getOrDefault(f.type(), new int[]{1, 1});
                    assertTrue(sz[0] >= 1 && sz[1] >= 1, key + " 家具 " + f.type() + " 尺寸非法");
                }
            }
        }
    }

    @Test
    @DisplayName("未知模板键 → 默认配方（桌 + 椅 + 灯 + 桌上便条）")
    void unknownKeyFallsBackToDefault() {
        StructureRoomTemplates.Recipe r = StructureRoomTemplates.recipe("no_such_template");
        assertEquals(StructureRoomTemplates.defaultRecipe(), r);
        assertNotNull(r.noteTarget());
        assertTrue(r.furniture().stream().anyMatch(f -> "table_rect".equals(f.type())));
        assertTrue(r.furniture().stream().anyMatch(f -> "note".equals(f.type())));
    }
}
