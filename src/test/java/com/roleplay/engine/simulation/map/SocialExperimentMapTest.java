package com.roleplay.engine.simulation.map;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocialExperimentMapTest {

    @Test
    void dawnTownMeetsMapContractAndContainsSocialLandmarks() {
        Map<String, Object> map = SocialExperimentMap.generate();
        MapValidator.Result result = MapValidator.validateMap(map);

        assertTrue(result.ok(), () -> "地图校验失败: " + result.errors());
        assertTrue(result.warnings().isEmpty(), () -> "地图不应有校验警告: " + result.warnings());
        assertEquals(SocialExperimentMap.WIDTH, map.get("width"));
        assertEquals(SocialExperimentMap.HEIGHT, map.get("height"));
        assertEquals(8, ((List<?>) map.get("spawn_points")).size());
        assertEquals(7, ((List<?>) map.get("zones")).size());
        assertEquals(6, ((List<?>) map.get("rooms")).size());

        Map<?, ?> layers = (Map<?, ?>) map.get("layers");
        List<?> collision = (List<?>) layers.get("collision");
        List<?> firstRow = (List<?>) collision.get(0);
        assertFalse(((Number) firstRow.get(1)).intValue() == 0, "地图边界必须封口");

        Map<?, ?> tileProps = (Map<?, ?>) map.get("tileProps");
        assertTrue(tileProps.containsKey("28,10"), "河流应提供可验证的水域属性");
    }
}
