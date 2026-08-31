package com.roleplay.engine.simulation.worlddefinition;

import com.roleplay.engine.simulation.navigation.portal.SemanticPortal;
import com.roleplay.engine.simulation.spatial.Transform3D;
import com.roleplay.engine.simulation.spatial.Vec3;

import java.util.List;
import java.util.Set;

/**
 * Engine-independent static world content. Mutable simulation facts belong in
 * {@link RuntimeWorldState}; Unity scene and prefab paths are deliberately absent.
 */
public record WorldDefinition(Metadata metadata,
                              List<Floor> floors,
                              List<Surface> surfaces,
                              List<Room> rooms,
                              List<Zone> zones,
                              List<SemanticPortal> portals,
                              List<EntityDefinition> entityDefinitions,
                              List<SpawnPoint> spawnPoints,
                              List<AcousticRegion> acousticRegions,
                              List<NavigationBakeReference> navigationBakeRefs,
                              List<AssetReference> assetRefs) {

    public static final int SCHEMA_VERSION = 2;

    public WorldDefinition {
        floors = immutable(floors);
        surfaces = immutable(surfaces);
        rooms = immutable(rooms);
        zones = immutable(zones);
        portals = immutable(portals);
        entityDefinitions = immutable(entityDefinitions);
        spawnPoints = immutable(spawnPoints);
        acousticRegions = immutable(acousticRegions);
        navigationBakeRefs = immutable(navigationBakeRefs);
        assetRefs = immutable(assetRefs);
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static <T> Set<T> immutable(Set<T> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    public record Metadata(String worldId, int schemaVersion, String name, long revision) { }

    public record Bounds3(Vec3 min, Vec3 max) {
        public boolean isFiniteAndOrdered() {
            return finite(min) && finite(max)
                    && min.x() <= max.x() && min.y() <= max.y() && min.z() <= max.z();
        }

        public boolean contains(Vec3 point) {
            return isFiniteAndOrdered() && finite(point)
                    && point.x() >= min.x() && point.x() <= max.x()
                    && point.y() >= min.y() && point.y() <= max.y()
                    && point.z() >= min.z() && point.z() <= max.z();
        }

        public boolean contains(Bounds3 other) {
            return other != null && other.isFiniteAndOrdered()
                    && contains(other.min()) && contains(other.max());
        }

        private static boolean finite(Vec3 value) {
            return value != null && Double.isFinite(value.x())
                    && Double.isFinite(value.y()) && Double.isFinite(value.z());
        }
    }

    public record Floor(String id, String name, double elevation, Bounds3 bounds) { }

    public record Surface(String id,
                          String floorId,
                          SurfaceKind kind,
                          Bounds3 bounds,
                          String navigationBakeRefId,
                          Set<String> tags) {
        public Surface {
            tags = immutable(tags);
        }
    }

    public enum SurfaceKind { GROUND, FLOOR, RAMP, PLATFORM, WATER, RESTRICTED }

    public record Room(String id,
                       String floorId,
                       List<String> surfaceIds,
                       List<String> zoneIds,
                       String acousticRegionId,
                       Bounds3 bounds,
                       Set<String> tags) {
        public Room {
            surfaceIds = immutable(surfaceIds);
            zoneIds = immutable(zoneIds);
            tags = immutable(tags);
        }
    }

    public record Zone(String id,
                       String floorId,
                       String roomId,
                       Bounds3 bounds,
                       Set<String> tags) {
        public Zone {
            tags = immutable(tags);
        }
    }

    public record EntityDefinition(String id,
                                   String type,
                                   String floorId,
                                   String surfaceId,
                                   Transform3D transform,
                                   String assetId,
                                   Set<String> tags) {
        public EntityDefinition {
            tags = immutable(tags);
        }
    }

    public record SpawnPoint(String id,
                             String floorId,
                             String surfaceId,
                             Vec3 position,
                             Set<String> tags) {
        public SpawnPoint {
            tags = immutable(tags);
        }
    }

    public record AcousticRegion(String id,
                                 String floorId,
                                 List<String> roomIds,
                                 double absorption,
                                 double transmission,
                                 Set<String> tags) {
        public AcousticRegion {
            roomIds = immutable(roomIds);
            tags = immutable(tags);
        }
    }

    /** Opaque server-side reference to a precomputed navigation artifact. */
    public record NavigationBakeReference(String id,
                                          String floorId,
                                          List<String> surfaceIds,
                                          String backend,
                                          String contentId,
                                          long revision) {
        public NavigationBakeReference {
            surfaceIds = immutable(surfaceIds);
        }
    }

    /** Logical asset identifiers only; no Unity scene or prefab filesystem path. */
    public record AssetReference(String assetId,
                                 String assetType,
                                 String catalogId,
                                 String version) { }
}
