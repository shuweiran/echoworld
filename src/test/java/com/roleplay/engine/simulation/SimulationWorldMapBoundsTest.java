package com.roleplay.engine.simulation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimulationWorldMapBoundsTest {

    @Test
    void default64x40MapUsesTilePixelsAsPhysicalBounds() {
        SimulationWorld world = new SimulationWorld();
        world.setWorldBounds(64 * 32, 40 * 32);
        assertEquals(2048, world.getWorldWidth());
        assertEquals(1280, world.getWorldHeight());
        assertEquals(2048, world.getMovementSystem().getWorldWidth());
        assertEquals(1280, world.getMovementSystem().getWorldHeight());
    }

    @Test
    void nonDefaultMapDimensionsRebuildSpatialAndMovementBounds() {
        SimulationWorld world = new SimulationWorld();
        world.setWorldBounds(32 * 32, 24 * 32);
        assertEquals(1024, world.getWorldWidth());
        assertEquals(768, world.getWorldHeight());
        world.setWorldBounds(96 * 32, 60 * 32);
        assertEquals(3072, world.getWorldWidth());
        assertEquals(1920, world.getWorldHeight());
    }

    @Test
    void tileSizeChangesPhysicalBoundsWithoutWorldCodeChanges() {
        SimulationWorld world = new SimulationWorld();
        world.setWorldBounds(32 * 16, 24 * 16);
        assertEquals(512, world.getWorldWidth());
        assertEquals(384, world.getWorldHeight());
        world.setWorldBounds(32 * 48, 24 * 48);
        assertEquals(1536, world.getWorldWidth());
        assertEquals(1152, world.getWorldHeight());
    }
}
