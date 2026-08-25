package com.roleplay.engine.service.world;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScenePopulationProfileTest {

    @Test
    void clampsLlmTargetToSemanticCategoryRange() {
        ScenePopulationProfile privateRoom = ScenePopulationProfile.validated(
                ScenePopulationCategory.PRIVATE_INDOOR, "卧室", 30, 0.95, "私人室内",
                24, 80, Instant.EPOCH);
        ScenePopulationProfile busyStreet = ScenePopulationProfile.validated(
                ScenePopulationCategory.OUTDOOR_BUSY, "节庆广场", 50, 0.9, "繁忙户外",
                24, 80, Instant.EPOCH);

        assertEquals(5, privateRoom.targetCount());
        assertEquals(36, busyStreet.targetCount());
    }

    @Test
    void lowConfidenceKeepsPreviousSafeBudget() {
        ScenePopulationProfile uncertain = ScenePopulationProfile.validated(
                ScenePopulationCategory.ISOLATED, "不确定", 0, 0.3, "上下文不足",
                12, 80, Instant.EPOCH);

        assertEquals(12, uncertain.targetCount());
    }
}
