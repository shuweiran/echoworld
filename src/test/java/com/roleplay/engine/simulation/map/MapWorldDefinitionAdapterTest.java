package com.roleplay.engine.simulation.map;

import com.roleplay.engine.simulation.SimulationWorld;
import com.roleplay.engine.simulation.worlddefinition.WorldDefinitionValidator;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MapWorldDefinitionAdapterTest {
    @Test
    void legacyMapBecomesOneGroundFloorWithoutChangingPixelBounds() {
        Map<String, Object> legacy = MapContract.emptyMap(4, 3, 16);
        MapWorldDefinitionAdapter.AdaptedWorld adapted = MapWorldDefinitionAdapter.adapt(legacy);

        assertEquals(List.of("ground"), adapted.definition().floors().stream().map(f -> f.id()).toList());
        assertEquals(64, adapted.worldWidth());
        assertEquals(48, adapted.worldHeight());
        assertEquals("ground", adapted.definition().spawnPoints().getFirst().floorId());
        assertTrue(new WorldDefinitionValidator().validate(adapted.definition()).valid());
    }

    @Test
    void threeFloorContractCreatesAuthoritativeFloorsObstaclesRoomsAndConnectors() {
        Map<String, Object> map = MapContract.emptyMap(4, 4, 10);
        List<List<Integer>> open = grid(4, 4, 0);
        List<List<Integer>> oneWall = new ArrayList<>(open);
        oneWall.set(0, List.of(1, 0, 0, 0));
        map.put("floors", List.of(
                floor("f1", open, List.of(Map.of("id", "lobby", "x", 0, "y", 0, "w", 2, "h", 2))),
                floor("f2", oneWall, List.of()),
                floor("f3", open, List.of())));
        map.put("connectors", List.of(
                connector("s12", "f1", "f2", 1),
                connector("s23", "f2", "f3", 2)));

        MapWorldDefinitionAdapter.AdaptedWorld adapted = MapWorldDefinitionAdapter.adapt(map);
        assertEquals(3, adapted.definition().floors().size());
        assertEquals(2, adapted.definition().portals().size());
        assertEquals("f2", adapted.obstacles().getFirst().getFloorId());
        assertEquals("lobby", adapted.definition().rooms().getFirst().id());
        assertTrue(new WorldDefinitionValidator().validate(adapted.definition()).valid());

        SimulationWorld world = new SimulationWorld();
        world.loadWorldDefinition(adapted.definition());
        world.setCustomObstacles(adapted.obstacles(), "adapter-test");
        SimulationWorld.WorldSnapshot snapshot = world.advanceOneTick();
        assertEquals(3, snapshot.floors().size());
        assertEquals(2, snapshot.connectors().size());
        assertTrue(snapshot.obstacles().stream().allMatch(obstacle -> obstacle.containsKey("floorId")));
    }

    private static Map<String, Object> floor(String id, List<List<Integer>> collision, List<Map<String, Object>> rooms) {
        return Map.of("id", id, "width", 4, "height", 4, "tile_size", 10,
                "collision", collision, "rooms", rooms);
    }

    private static Map<String, Object> connector(String id, String from, String to, int x) {
        return Map.of("id", id, "kind", "stairs", "sourceFloor", from, "source", List.of(x, 1),
                "targetFloor", to, "target", List.of(x, 1), "bidirectional", true);
    }

    private static List<List<Integer>> grid(int width, int height, int value) {
        List<List<Integer>> result = new ArrayList<>();
        for (int y = 0; y < height; y++) result.add(new ArrayList<>(Collections.nCopies(width, value)));
        return result;
    }
}
