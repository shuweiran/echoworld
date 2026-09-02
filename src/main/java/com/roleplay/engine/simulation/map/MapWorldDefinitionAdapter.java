package com.roleplay.engine.simulation.map;

import com.roleplay.engine.simulation.Obstacle;
import com.roleplay.engine.simulation.navigation.portal.PortalEndpoint;
import com.roleplay.engine.simulation.navigation.portal.SemanticPortal;
import com.roleplay.engine.simulation.spatial.Vec3;
import com.roleplay.engine.simulation.worlddefinition.WorldDefinition;
import com.roleplay.engine.simulation.action.ActionType;
import com.roleplay.engine.simulation.worldobject.AffordanceDefinition;
import com.roleplay.engine.simulation.worldobject.WorldObject;
import com.roleplay.engine.simulation.spatial.Transform3D;

import java.util.*;

/** Converts the tolerant tile MapContract into one server-authoritative multi-floor definition. */
public final class MapWorldDefinitionAdapter {
    public record AdaptedWorld(WorldDefinition definition, List<Obstacle> obstacles,
                               double worldWidth, double worldHeight, List<WorldObject> worldObjects) {
        public AdaptedWorld(WorldDefinition definition, List<Obstacle> obstacles,
                            double worldWidth, double worldHeight) {
            this(definition, obstacles, worldWidth, worldHeight, List.of());
        }
    }

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

        List<WorldObject> worldObjects = mapWorldObjects(map, facts);

        WorldDefinition definition = new WorldDefinition(
                new WorldDefinition.Metadata(text(map.get("map_id"), "map"), WorldDefinition.SCHEMA_VERSION,
                        text(map.get("name"), "map"), 1), floors, surfaces, rooms, List.of(), portals,
                List.of(), spawns, List.of(), List.of(), List.of());
        return new AdaptedWorld(definition, List.copyOf(obstacles), maxWidth, maxHeight, worldObjects);
    }

    private static List<WorldObject> mapWorldObjects(Map<String, Object> map, Map<String, FloorFacts> facts) {
        List<WorldObject> result = new ArrayList<>();
        Object rawDecor = map.get("decor");
        if (!(rawDecor instanceof List<?> decor)) return List.of();
        for (Object item : decor) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            String id = text(raw.get("id"), "");
            String type = text(raw.get("type"), "object").toLowerCase(Locale.ROOT);
            String floorId = text(raw.get("floorId"), "ground");
            FloorFacts floor = facts.getOrDefault(floorId, facts.values().iterator().next());
            Object tile = raw.get("tile");
            if (id.isBlank() || !(tile instanceof List<?> point) || point.size() < 2) continue;
            Vec3 position = point(tile, floor);
            boolean portable = portable(type, raw);
            double radiusTiles = Math.max(1, number(raw.get("radius"), 1));
            double distance = radiusTiles * floor.tileSize();
            Map<ActionType, AffordanceDefinition> affordances = new EnumMap<>(ActionType.class);
            affordances.put(ActionType.LOOK_AT, affordance(ActionType.LOOK_AT, distance));
            if (portable) {
                affordances.put(ActionType.PICK_UP, affordance(ActionType.PICK_UP, distance));
                affordances.put(ActionType.PUT_DOWN, affordance(ActionType.PUT_DOWN, distance));
            }
            if (hasUse(type, raw)) affordances.put(ActionType.USE, affordance(ActionType.USE, distance));
            if (Set.of("chest", "cabinet", "door", "gate").contains(type)) {
                affordances.put(ActionType.OPEN, new AffordanceDefinition(ActionType.OPEN, distance, 0, 1,
                        Map.of("open", "false")));
                affordances.put(ActionType.CLOSE, new AffordanceDefinition(ActionType.CLOSE, distance, 0, 1,
                        Map.of("open", "true")));
            }
            if (Set.of("chair", "bench", "stool").contains(type)) {
                affordances.put(ActionType.SIT, affordance(ActionType.SIT, distance));
            }
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("displayName", text(first(raw.get("name"), raw.get("label")), displayName(type)));
            properties.put("description", text(raw.get("description"), ""));
            properties.put("floorId", floor.id());
            properties.put("portable", portable);
            Map<String, Object> initialState = new LinkedHashMap<>();
            if (raw.get("state") instanceof Map<?, ?> configuredState) {
                configuredState.forEach((key, value) -> initialState.put(String.valueOf(key), value));
            }
            if (Set.of("chest", "cabinet", "door", "gate").contains(type)) initialState.putIfAbsent("open", false);
            if (!initialState.isEmpty()) properties.put("initialState", initialState);
            Map<String, Double> effects = useEffects(type, raw);
            if (!effects.isEmpty()) properties.put("useEffects", effects);
            boolean consumable = Boolean.TRUE.equals(raw.get("consumable"))
                    || Set.of("food", "drink", "potion", "herb", "apple", "bread").contains(type);
            properties.put("consumable", consumable);
            Set<String> tags = new LinkedHashSet<>();
            tags.add("map-decor"); tags.add(type);
            if (portable) tags.add("item");
            result.add(new WorldObject(id, type.toUpperCase(Locale.ROOT),
                    new Transform3D(position, com.roleplay.engine.simulation.spatial.Quaternion.identity()),
                    affordances, tags, properties));
        }
        return List.copyOf(result);
    }

    private static boolean portable(String type, Map<?, ?> raw) {
        if (raw.get("portable") instanceof Boolean value) return value;
        return Set.of("note", "key", "book", "bottle", "food", "drink", "potion", "herb",
                "apple", "bread", "coin", "clue", "tool", "letter", "scroll").contains(type);
    }

    private static boolean hasUse(String type, Map<?, ?> raw) {
        return raw.get("onInteract") instanceof Map<?, ?> || raw.get("useEffects") instanceof Map<?, ?>
                || Set.of("food", "drink", "potion", "herb", "apple", "bread", "book", "note", "letter").contains(type);
    }

    private static Map<String, Double> useEffects(String type, Map<?, ?> raw) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (raw.get("useEffects") instanceof Map<?, ?> configured) {
            configured.forEach((key, value) -> { if (value instanceof Number n) result.put(String.valueOf(key), n.doubleValue()); });
        }
        if (!result.isEmpty()) return Map.copyOf(result);
        if (Set.of("food", "apple", "bread", "herb").contains(type)) {
            result.put("hunger", -20.0); result.put("stamina", 8.0);
        } else if (Set.of("drink", "potion", "bottle").contains(type)) {
            result.put("stamina", 15.0); result.put("focus", 5.0);
        } else if (Set.of("book", "note", "letter").contains(type)) {
            result.put("insight", 0.25); result.put("focus", 2.0);
        }
        return Map.copyOf(result);
    }

    private static String displayName(String type) {
        return switch (type) {
            case "note" -> "便笺"; case "key" -> "钥匙"; case "book" -> "书";
            case "bottle" -> "瓶子"; case "food" -> "食物"; case "drink" -> "饮品";
            case "potion" -> "药剂"; case "herb" -> "草药"; case "coin" -> "硬币";
            case "chest" -> "箱子"; case "bench" -> "长椅"; case "chair" -> "椅子";
            default -> type.replace('_', ' ');
        };
    }

    private static AffordanceDefinition affordance(ActionType action, double distance) {
        return new AffordanceDefinition(action, distance, 0, 1, Map.of());
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
