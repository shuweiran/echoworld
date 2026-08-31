package com.roleplay.engine.simulation.map;

import com.roleplay.engine.simulation.Obstacle;
import com.roleplay.engine.simulation.navigation.portal.PortalEndpoint;
import com.roleplay.engine.simulation.navigation.portal.SemanticPortal;
import com.roleplay.engine.simulation.spatial.Vec3;
import com.roleplay.engine.simulation.worlddefinition.WorldDefinition;

import java.util.*;

/** Converts the tolerant tile MapContract into one server-authoritative multi-floor definition. */
public final class MapWorldDefinitionAdapter {
    public record AdaptedWorld(WorldDefinition definition, List<Obstacle> obstacles,
                               double worldWidth, double worldHeight) { }

    private MapWorldDefinitionAdapter() { }

    public static AdaptedWorld adapt(Map<String, Object> raw) {
        Map<String, Object> map = MapContract.normalize(raw);
        MapValidator.Result validation = MapValidator.validateMap(map);
        if (!validation.ok()) throw new IllegalArgumentException("invalid map contract: " + validation.errors());

        List<?> floorMaps = (List<?>) map.get("floors");
        List<WorldDefinition.Floor> floors = new ArrayList<>();
        List<WorldDefinition.Surface> surfaces = new ArrayList<>();
        List<WorldDefinition.Room> rooms = new ArrayList<>();
        List<Obstacle> obstacles = new ArrayList<>();
        Map<String, FloorFacts> facts = new LinkedHashMap<>();
        double maxWidth = 0;
        double maxHeight = 0;
        for (int index = 0; index < floorMaps.size(); index++) {
            Map<?, ?> floorMap = (Map<?, ?>) floorMaps.get(index);
            String id = text(floorMap.get("id"), "ground");
            int width = MapContract.intOf(floorMap.get("width"), MapContract.intOf(map.get("width"), 0));
            int height = MapContract.intOf(floorMap.get("height"), MapContract.intOf(map.get("height"), 0));
            int tileSize = MapContract.intOf(first(floorMap.get("tileSize"), floorMap.get("tile_size")),
                    MapContract.intOf(map.get("tile_size"), MapContract.DEFAULT_TILE_SIZE));
            double elevation = number(floorMap.get("elevation"), index * 3.2);
            double worldWidth = width * (double) tileSize;
            double worldHeight = height * (double) tileSize;
            WorldDefinition.Bounds3 bounds = new WorldDefinition.Bounds3(
                    new Vec3(0, elevation, 0), new Vec3(worldWidth, elevation + 1, worldHeight));
            String surfaceId = "surface:" + id;
            floors.add(new WorldDefinition.Floor(id, text(floorMap.get("name"), id), elevation, bounds));
            surfaces.add(new WorldDefinition.Surface(surfaceId, id, WorldDefinition.SurfaceKind.FLOOR,
                    bounds, "", Set.of("tile-map")));
            int[][] collision = floorCollision(floorMap, map, id);
            if (collision != null) {
                obstacles.addAll(Obstacle.fromCollisionGrid(collision, tileSize,
                        text(map.get("name"), "map") + ":" + id, worldWidth, worldHeight, id));
            }
            facts.put(id, new FloorFacts(id, surfaceId, width, height, tileSize, elevation, bounds, collision));
            maxWidth = Math.max(maxWidth, worldWidth);
            maxHeight = Math.max(maxHeight, worldHeight);
            Object floorRooms = floorMap.get("rooms");
            if (floorRooms instanceof List<?> list) addRooms(rooms, list, facts.get(id));
        }
        if (facts.containsKey("ground") && map.get("rooms") instanceof List<?> topRooms) {
            addRooms(rooms, topRooms, facts.get("ground"));
        }

        List<SemanticPortal> portals = new ArrayList<>();
        for (Object item : (List<?>) map.get("connectors")) {
            Map<?, ?> connector = (Map<?, ?>) item;
            String id = text(connector.get("id"), "");
            FloorFacts source = facts.get(text(connector.get("sourceFloor"), "ground"));
            FloorFacts target = facts.get(text(connector.get("targetFloor"), "ground"));
            Vec3 sourcePosition = point(connector.get("source"), source);
            Vec3 targetPosition = point(connector.get("target"), target);
            SemanticPortal.Kind kind = kind(first(connector.get("kind"), connector.get("type")));
            Set<String> tags = stringSet(connector.get("tags"));
            if (Boolean.TRUE.equals(connector.get("acoustic")) || kind == SemanticPortal.Kind.STAIRS) {
                tags = new LinkedHashSet<>(tags);
                tags.add("acoustic");
            }
            portals.add(new SemanticPortal(id, kind,
                    new PortalEndpoint(source.id(), source.surfaceId(), sourcePosition),
                    new PortalEndpoint(target.id(), target.surfaceId(), targetPosition),
                    Boolean.TRUE.equals(connector.get("bidirectional")),
                    number(connector.get("traversalCost"), 10), text(connector.get("interactionAction"), ""), tags));
        }

        List<WorldDefinition.SpawnPoint> spawns = new ArrayList<>();
        if (map.get("spawn_points") instanceof List<?> spawnMaps) {
            int index = 0;
            for (Object item : spawnMaps) {
                if (!(item instanceof Map<?, ?> spawn)) continue;
                FloorFacts floor = facts.get(text(spawn.get("floorId"), facts.keySet().iterator().next()));
                Object rawPoint = first(spawn.get("position"), spawn.get("tile"));
                if (rawPoint == null) rawPoint = List.of(MapContract.intOf(spawn.get("x"), 0), MapContract.intOf(spawn.get("y"), 0));
                spawns.add(new WorldDefinition.SpawnPoint(text(spawn.get("id"), "spawn-" + index++),
                        floor.id(), floor.surfaceId(), point(rawPoint, floor), stringSet(spawn.get("tags"))));
            }
        }
        if (spawns.isEmpty()) {
            FloorFacts firstFloor = facts.values().iterator().next();
            int[] tile = firstWalkable(firstFloor.collision(), firstFloor.width(), firstFloor.height());
            spawns.add(new WorldDefinition.SpawnPoint("spawn-default", firstFloor.id(), firstFloor.surfaceId(),
                    point(List.of(tile[0], tile[1]), firstFloor), Set.of("default")));
        }

        WorldDefinition definition = new WorldDefinition(
                new WorldDefinition.Metadata(text(map.get("map_id"), "map"), WorldDefinition.SCHEMA_VERSION,
                        text(map.get("name"), "map"), 1), floors, surfaces, rooms, List.of(), portals,
                List.of(), spawns, List.of(), List.of(), List.of());
        return new AdaptedWorld(definition, List.copyOf(obstacles), maxWidth, maxHeight);
    }

    private static int[][] floorCollision(Map<?, ?> floor, Map<String, Object> map, String id) {
        Object collision = floor.get("collision");
        if (collision == null && floor.get("layers") instanceof Map<?, ?> layers) collision = layers.get("collision");
        if (collision == null && "ground".equals(id) && map.get("layers") instanceof Map<?, ?> layers) {
            collision = layers.get("collision");
        }
        return MapContract.intGrid(collision);
    }

    private static void addRooms(List<WorldDefinition.Room> out, List<?> roomMaps, FloorFacts floor) {
        int index = 0;
        for (Object item : roomMaps) {
            if (!(item instanceof Map<?, ?> room)) continue;
            double x = MapContract.intOf(room.get("x"), 0) * floor.tileSize();
            double z = MapContract.intOf(room.get("y"), 0) * floor.tileSize();
            double width = MapContract.intOf(room.get("w"), 1) * floor.tileSize();
            double height = MapContract.intOf(room.get("h"), 1) * floor.tileSize();
            WorldDefinition.Bounds3 bounds = new WorldDefinition.Bounds3(
                    new Vec3(x, floor.elevation(), z), new Vec3(x + width, floor.elevation() + 1, z + height));
            out.add(new WorldDefinition.Room(text(room.get("id"), floor.id() + ":room-" + index++), floor.id(),
                    List.of(floor.surfaceId()), List.of(), "", bounds, stringSet(room.get("tags"))));
        }
    }

    private static Vec3 point(Object raw, FloorFacts floor) {
        List<?> point = (List<?>) raw;
        double x = ((Number) point.get(0)).doubleValue() * floor.tileSize() + floor.tileSize() / 2.0;
        double z = ((Number) point.get(1)).doubleValue() * floor.tileSize() + floor.tileSize() / 2.0;
        return new Vec3(x, floor.elevation(), z);
    }

    private static SemanticPortal.Kind kind(Object raw) {
        String value = text(raw, "stairs").toUpperCase(Locale.ROOT);
        if (value.equals("PORTAL")) return SemanticPortal.Kind.LINK;
        try { return SemanticPortal.Kind.valueOf(value); }
        catch (IllegalArgumentException ignored) { return SemanticPortal.Kind.LINK; }
    }

    private static Set<String> stringSet(Object raw) {
        if (!(raw instanceof Collection<?> values)) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        values.forEach(value -> result.add(String.valueOf(value)));
        return result;
    }

    private static Object first(Object a, Object b) { return a != null ? a : b; }
    private static String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }
    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private static int[] firstWalkable(int[][] collision, int width, int height) {
        int centerX = Math.max(0, width / 2), centerY = Math.max(0, height / 2);
        if (collision == null || collision.length == 0) return new int[]{centerX, centerY};
        for (int radius = 0; radius < Math.max(width, height); radius++) {
            for (int y = Math.max(0, centerY - radius); y <= Math.min(height - 1, centerY + radius); y++) {
                for (int x = Math.max(0, centerX - radius); x <= Math.min(width - 1, centerX + radius); x++) {
                    if (y < collision.length && collision[y] != null && x < collision[y].length
                            && collision[y][x] == 0) return new int[]{x, y};
                }
            }
        }
        return new int[]{0, 0};
    }

    private record FloorFacts(String id, String surfaceId, int width, int height, int tileSize,
                              double elevation, WorldDefinition.Bounds3 bounds, int[][] collision) { }
}
