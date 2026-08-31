package com.roleplay.engine.simulation.worlddefinition;

import com.roleplay.engine.simulation.navigation.portal.PortalEndpoint;
import com.roleplay.engine.simulation.navigation.portal.PortalRuntimeState;
import com.roleplay.engine.simulation.navigation.portal.SemanticPortal;
import com.roleplay.engine.simulation.spatial.Quaternion;
import com.roleplay.engine.simulation.spatial.Transform3D;
import com.roleplay.engine.simulation.spatial.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Referential and geometric validation for WorldDefinition V2 and its runtime projection. */
public final class WorldDefinitionValidator {

    public Result validate(WorldDefinition definition) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (definition == null) {
            return Result.invalid(List.of("WORLD_DEFINITION_REQUIRED"), List.of());
        }

        validateMetadata(definition.metadata(), errors);
        Map<String, WorldDefinition.Floor> floors = index(
                "FLOOR", definition.floors(), WorldDefinition.Floor::id, errors);
        Map<String, WorldDefinition.Surface> surfaces = index(
                "SURFACE", definition.surfaces(), WorldDefinition.Surface::id, errors);
        Map<String, WorldDefinition.Room> rooms = index(
                "ROOM", definition.rooms(), WorldDefinition.Room::id, errors);
        Map<String, WorldDefinition.Zone> zones = index(
                "ZONE", definition.zones(), WorldDefinition.Zone::id, errors);
        Map<String, SemanticPortal> portals = index(
                "PORTAL", definition.portals(), SemanticPortal::id, errors);
        Map<String, WorldDefinition.EntityDefinition> entities = index(
                "ENTITY_DEFINITION", definition.entityDefinitions(),
                WorldDefinition.EntityDefinition::id, errors);
        Map<String, WorldDefinition.SpawnPoint> spawns = index(
                "SPAWN_POINT", definition.spawnPoints(), WorldDefinition.SpawnPoint::id, errors);
        Map<String, WorldDefinition.AcousticRegion> acoustics = index(
                "ACOUSTIC_REGION", definition.acousticRegions(),
                WorldDefinition.AcousticRegion::id, errors);
        Map<String, WorldDefinition.NavigationBakeReference> bakes = index(
                "NAV_BAKE", definition.navigationBakeRefs(),
                WorldDefinition.NavigationBakeReference::id, errors);
        Map<String, WorldDefinition.AssetReference> assets = index(
                "ASSET", definition.assetRefs(), WorldDefinition.AssetReference::assetId, errors);

        validateFloors(floors, errors);
        validateSurfaces(surfaces, floors, bakes, errors);
        validateRooms(rooms, floors, surfaces, zones, acoustics, errors);
        validateZones(zones, floors, rooms, errors);
        validatePortals(portals, floors, surfaces, errors, warnings);
        validateEntities(entities, floors, surfaces, assets, errors);
        validateSpawns(spawns, floors, surfaces, errors);
        validateAcoustics(acoustics, floors, rooms, errors);
        validateBakes(bakes, floors, surfaces, errors);
        validateAssets(assets, errors);
        return new Result(errors.isEmpty(), sorted(errors), sorted(warnings));
    }

    public Result validateRuntimeState(WorldDefinition definition, RuntimeWorldState state) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Result staticResult = validate(definition);
        errors.addAll(staticResult.errors());
        warnings.addAll(staticResult.warnings());
        if (state == null) {
            errors.add("RUNTIME_STATE_REQUIRED");
            return new Result(false, sorted(errors), sorted(warnings));
        }
        String worldId = definition == null || definition.metadata() == null
                ? null : definition.metadata().worldId();
        if (blank(state.worldDefinitionId()) || !state.worldDefinitionId().equals(worldId)) {
            errors.add("RUNTIME_WORLD_DEFINITION_MISMATCH");
        }
        if (state.tick() < 0) {
            errors.add("RUNTIME_TICK_NEGATIVE");
        }

        Map<String, WorldDefinition.EntityDefinition> entities = definition == null
                ? Map.of() : indexWithoutErrors(definition.entityDefinitions(),
                WorldDefinition.EntityDefinition::id);
        for (Map.Entry<String, RuntimeWorldState.EntityRuntimeState> entry
                : state.entityStates().entrySet()) {
            RuntimeWorldState.EntityRuntimeState entityState = entry.getValue();
            if (blank(entry.getKey()) || entityState == null) {
                errors.add("RUNTIME_ENTITY_STATE_INVALID:" + entry.getKey());
                continue;
            }
            if (!entities.containsKey(entityState.definitionId())) {
                errors.add("RUNTIME_ENTITY_DEFINITION_UNKNOWN:" + entry.getKey());
            }
            if (!validTransform(entityState.transform())) {
                errors.add("RUNTIME_ENTITY_TRANSFORM_INVALID:" + entry.getKey());
            }
            if (entityState.revision() < 0) {
                errors.add("RUNTIME_ENTITY_REVISION_NEGATIVE:" + entry.getKey());
            }
        }

        Map<String, SemanticPortal> portals = definition == null
                ? Map.of() : indexWithoutErrors(definition.portals(), SemanticPortal::id);
        for (Map.Entry<String, PortalRuntimeState> entry : state.portalStates().entrySet()) {
            PortalRuntimeState portalState = entry.getValue();
            if (blank(entry.getKey()) || portalState == null) {
                errors.add("RUNTIME_PORTAL_STATE_INVALID:" + entry.getKey());
                continue;
            }
            if (!portals.containsKey(entry.getKey())) {
                errors.add("RUNTIME_PORTAL_UNKNOWN:" + entry.getKey());
            }
            if (!entry.getKey().equals(portalState.portalId())) {
                errors.add("RUNTIME_PORTAL_ID_MISMATCH:" + entry.getKey());
            }
            if (portalState.revision() < 0) {
                errors.add("RUNTIME_PORTAL_REVISION_NEGATIVE:" + entry.getKey());
            }
        }
        return new Result(errors.isEmpty(), sorted(errors), sorted(warnings));
    }

    private void validateMetadata(WorldDefinition.Metadata metadata, List<String> errors) {
        if (metadata == null) {
            errors.add("METADATA_REQUIRED");
            return;
        }
        requireText("METADATA_WORLD_ID", metadata.worldId(), errors);
        requireText("METADATA_NAME", metadata.name(), errors);
        if (metadata.schemaVersion() != WorldDefinition.SCHEMA_VERSION) {
            errors.add("SCHEMA_VERSION_UNSUPPORTED:" + metadata.schemaVersion());
        }
        if (metadata.revision() < 0) {
            errors.add("METADATA_REVISION_NEGATIVE");
        }
    }

    private void validateFloors(Map<String, WorldDefinition.Floor> floors, List<String> errors) {
        for (WorldDefinition.Floor floor : floors.values()) {
            requireText("FLOOR_NAME:" + floor.id(), floor.name(), errors);
            if (!Double.isFinite(floor.elevation())) {
                errors.add("FLOOR_ELEVATION_INVALID:" + floor.id());
            }
            validateBounds("FLOOR_BOUNDS:" + floor.id(), floor.bounds(), errors);
        }
    }

    private void validateSurfaces(Map<String, WorldDefinition.Surface> surfaces,
                                  Map<String, WorldDefinition.Floor> floors,
                                  Map<String, WorldDefinition.NavigationBakeReference> bakes,
                                  List<String> errors) {
        for (WorldDefinition.Surface surface : surfaces.values()) {
            WorldDefinition.Floor floor = floors.get(surface.floorId());
            if (floor == null) {
                errors.add("SURFACE_FLOOR_UNKNOWN:" + surface.id());
            }
            if (surface.kind() == null) {
                errors.add("SURFACE_KIND_REQUIRED:" + surface.id());
            }
            validateBounds("SURFACE_BOUNDS:" + surface.id(), surface.bounds(), errors);
            if (floor != null && floor.bounds() != null && surface.bounds() != null
                    && floor.bounds().isFiniteAndOrdered() && surface.bounds().isFiniteAndOrdered()
                    && !floor.bounds().contains(surface.bounds())) {
                errors.add("SURFACE_OUTSIDE_FLOOR:" + surface.id());
            }
            if (!blank(surface.navigationBakeRefId())) {
                WorldDefinition.NavigationBakeReference bake = bakes.get(surface.navigationBakeRefId());
                if (bake == null) {
                    errors.add("SURFACE_NAV_BAKE_UNKNOWN:" + surface.id());
                } else if (!bake.surfaceIds().contains(surface.id())) {
                    errors.add("SURFACE_NAV_BAKE_BACKREF_MISSING:" + surface.id());
                }
            }
        }
    }

    private void validateRooms(Map<String, WorldDefinition.Room> rooms,
                               Map<String, WorldDefinition.Floor> floors,
                               Map<String, WorldDefinition.Surface> surfaces,
                               Map<String, WorldDefinition.Zone> zones,
                               Map<String, WorldDefinition.AcousticRegion> acoustics,
                               List<String> errors) {
        for (WorldDefinition.Room room : rooms.values()) {
            WorldDefinition.Floor floor = floors.get(room.floorId());
            if (floor == null) {
                errors.add("ROOM_FLOOR_UNKNOWN:" + room.id());
            }
            validateBounds("ROOM_BOUNDS:" + room.id(), room.bounds(), errors);
            if (floor != null && floor.bounds() != null && room.bounds() != null
                    && floor.bounds().isFiniteAndOrdered() && room.bounds().isFiniteAndOrdered()
                    && !floor.bounds().contains(room.bounds())) {
                errors.add("ROOM_OUTSIDE_FLOOR:" + room.id());
            }
            for (String surfaceId : room.surfaceIds()) {
                WorldDefinition.Surface surface = surfaces.get(surfaceId);
                if (surface == null) {
                    errors.add("ROOM_SURFACE_UNKNOWN:" + room.id() + ":" + surfaceId);
                } else if (!room.floorId().equals(surface.floorId())) {
                    errors.add("ROOM_SURFACE_FLOOR_MISMATCH:" + room.id() + ":" + surfaceId);
                }
            }
            for (String zoneId : room.zoneIds()) {
                WorldDefinition.Zone zone = zones.get(zoneId);
                if (zone == null) {
                    errors.add("ROOM_ZONE_UNKNOWN:" + room.id() + ":" + zoneId);
                } else if (!room.id().equals(zone.roomId())) {
                    errors.add("ROOM_ZONE_BACKREF_MISMATCH:" + room.id() + ":" + zoneId);
                }
            }
            if (!blank(room.acousticRegionId())) {
                WorldDefinition.AcousticRegion acoustic = acoustics.get(room.acousticRegionId());
                if (acoustic == null) {
                    errors.add("ROOM_ACOUSTIC_REGION_UNKNOWN:" + room.id());
                } else if (!acoustic.roomIds().contains(room.id())) {
                    errors.add("ROOM_ACOUSTIC_BACKREF_MISSING:" + room.id());
                }
            }
        }
    }

    private void validateZones(Map<String, WorldDefinition.Zone> zones,
                               Map<String, WorldDefinition.Floor> floors,
                               Map<String, WorldDefinition.Room> rooms,
                               List<String> errors) {
        for (WorldDefinition.Zone zone : zones.values()) {
            WorldDefinition.Floor floor = floors.get(zone.floorId());
            if (floor == null) {
                errors.add("ZONE_FLOOR_UNKNOWN:" + zone.id());
            }
            validateBounds("ZONE_BOUNDS:" + zone.id(), zone.bounds(), errors);
            if (floor != null && floor.bounds() != null && zone.bounds() != null
                    && floor.bounds().isFiniteAndOrdered() && zone.bounds().isFiniteAndOrdered()
                    && !floor.bounds().contains(zone.bounds())) {
                errors.add("ZONE_OUTSIDE_FLOOR:" + zone.id());
            }
            if (!blank(zone.roomId())) {
                WorldDefinition.Room room = rooms.get(zone.roomId());
                if (room == null) {
                    errors.add("ZONE_ROOM_UNKNOWN:" + zone.id());
                } else if (!zone.floorId().equals(room.floorId())) {
                    errors.add("ZONE_ROOM_FLOOR_MISMATCH:" + zone.id());
                }
            }
        }
    }

    private void validatePortals(Map<String, SemanticPortal> portals,
                                 Map<String, WorldDefinition.Floor> floors,
                                 Map<String, WorldDefinition.Surface> surfaces,
                                 List<String> errors,
                                 List<String> warnings) {
        for (SemanticPortal portal : portals.values()) {
            if (portal.kind() == null) {
                errors.add("PORTAL_KIND_REQUIRED:" + portal.id());
            }
            if (!Double.isFinite(portal.traversalCost()) || portal.traversalCost() < 0) {
                errors.add("PORTAL_COST_INVALID:" + portal.id());
            }
            validateEndpoint(portal.id(), "A", portal.endpointA(), floors, surfaces, errors);
            validateEndpoint(portal.id(), "B", portal.endpointB(), floors, surfaces, errors);
            if (portal.endpointA() != null && portal.endpointB() != null
                    && portal.endpointA().surfaceKey().equals(portal.endpointB().surfaceKey())) {
                warnings.add("PORTAL_SAME_SURFACE_REDUNDANT:" + portal.id());
            }
            if (portal.kind() == SemanticPortal.Kind.DOOR && blank(portal.interactionAction())) {
                errors.add("PORTAL_DOOR_INTERACTION_REQUIRED:" + portal.id());
            }
        }
    }

    private void validateEndpoint(String portalId,
                                  String side,
                                  PortalEndpoint endpoint,
                                  Map<String, WorldDefinition.Floor> floors,
                                  Map<String, WorldDefinition.Surface> surfaces,
                                  List<String> errors) {
        String label = portalId + ":" + side;
        if (endpoint == null) {
            errors.add("PORTAL_ENDPOINT_REQUIRED:" + label);
            return;
        }
        WorldDefinition.Floor floor = floors.get(endpoint.floorId());
        WorldDefinition.Surface surface = surfaces.get(endpoint.surfaceId());
        if (floor == null) {
            errors.add("PORTAL_FLOOR_UNKNOWN:" + label);
        }
        if (surface == null) {
            errors.add("PORTAL_SURFACE_UNKNOWN:" + label);
        } else {
            if (!surface.floorId().equals(endpoint.floorId())) {
                errors.add("PORTAL_SURFACE_FLOOR_MISMATCH:" + label);
            }
            if (surface.bounds() != null && surface.bounds().isFiniteAndOrdered()
                    && !surface.bounds().contains(endpoint.worldPosition())) {
                errors.add("PORTAL_ENDPOINT_OUTSIDE_SURFACE:" + label);
            }
        }
        if (!finite(endpoint.worldPosition())) {
            errors.add("PORTAL_POSITION_INVALID:" + label);
        }
    }

    private void validateEntities(Map<String, WorldDefinition.EntityDefinition> entities,
                                  Map<String, WorldDefinition.Floor> floors,
                                  Map<String, WorldDefinition.Surface> surfaces,
                                  Map<String, WorldDefinition.AssetReference> assets,
                                  List<String> errors) {
        for (WorldDefinition.EntityDefinition entity : entities.values()) {
            requireText("ENTITY_TYPE:" + entity.id(), entity.type(), errors);
            WorldDefinition.Floor floor = floors.get(entity.floorId());
            WorldDefinition.Surface surface = surfaces.get(entity.surfaceId());
            if (floor == null) {
                errors.add("ENTITY_FLOOR_UNKNOWN:" + entity.id());
            }
            if (surface == null) {
                errors.add("ENTITY_SURFACE_UNKNOWN:" + entity.id());
            } else if (!surface.floorId().equals(entity.floorId())) {
                errors.add("ENTITY_SURFACE_FLOOR_MISMATCH:" + entity.id());
            }
            if (!validTransform(entity.transform())) {
                errors.add("ENTITY_TRANSFORM_INVALID:" + entity.id());
            } else if (surface != null && surface.bounds() != null
                    && surface.bounds().isFiniteAndOrdered()
                    && !surface.bounds().contains(entity.transform().position())) {
                errors.add("ENTITY_OUTSIDE_SURFACE:" + entity.id());
            }
            if (!blank(entity.assetId()) && !assets.containsKey(entity.assetId())) {
                errors.add("ENTITY_ASSET_UNKNOWN:" + entity.id());
            }
        }
    }

    private void validateSpawns(Map<String, WorldDefinition.SpawnPoint> spawns,
                                Map<String, WorldDefinition.Floor> floors,
                                Map<String, WorldDefinition.Surface> surfaces,
                                List<String> errors) {
        for (WorldDefinition.SpawnPoint spawn : spawns.values()) {
            if (!floors.containsKey(spawn.floorId())) {
                errors.add("SPAWN_FLOOR_UNKNOWN:" + spawn.id());
            }
            WorldDefinition.Surface surface = surfaces.get(spawn.surfaceId());
            if (surface == null) {
                errors.add("SPAWN_SURFACE_UNKNOWN:" + spawn.id());
            } else {
                if (!surface.floorId().equals(spawn.floorId())) {
                    errors.add("SPAWN_SURFACE_FLOOR_MISMATCH:" + spawn.id());
                }
                if (surface.bounds() != null && surface.bounds().isFiniteAndOrdered()
                        && !surface.bounds().contains(spawn.position())) {
                    errors.add("SPAWN_OUTSIDE_SURFACE:" + spawn.id());
                }
            }
            if (!finite(spawn.position())) {
                errors.add("SPAWN_POSITION_INVALID:" + spawn.id());
            }
        }
    }

    private void validateAcoustics(Map<String, WorldDefinition.AcousticRegion> acoustics,
                                   Map<String, WorldDefinition.Floor> floors,
                                   Map<String, WorldDefinition.Room> rooms,
                                   List<String> errors) {
        for (WorldDefinition.AcousticRegion acoustic : acoustics.values()) {
            if (!floors.containsKey(acoustic.floorId())) {
                errors.add("ACOUSTIC_FLOOR_UNKNOWN:" + acoustic.id());
            }
            if (!unitInterval(acoustic.absorption())) {
                errors.add("ACOUSTIC_ABSORPTION_INVALID:" + acoustic.id());
            }
            if (!unitInterval(acoustic.transmission())) {
                errors.add("ACOUSTIC_TRANSMISSION_INVALID:" + acoustic.id());
            }
            for (String roomId : acoustic.roomIds()) {
                WorldDefinition.Room room = rooms.get(roomId);
                if (room == null) {
                    errors.add("ACOUSTIC_ROOM_UNKNOWN:" + acoustic.id() + ":" + roomId);
                } else if (!acoustic.floorId().equals(room.floorId())) {
                    errors.add("ACOUSTIC_ROOM_FLOOR_MISMATCH:" + acoustic.id() + ":" + roomId);
                }
            }
        }
    }

    private void validateBakes(Map<String, WorldDefinition.NavigationBakeReference> bakes,
                               Map<String, WorldDefinition.Floor> floors,
                               Map<String, WorldDefinition.Surface> surfaces,
                               List<String> errors) {
        for (WorldDefinition.NavigationBakeReference bake : bakes.values()) {
            if (!floors.containsKey(bake.floorId())) {
                errors.add("NAV_BAKE_FLOOR_UNKNOWN:" + bake.id());
            }
            requireText("NAV_BAKE_BACKEND:" + bake.id(), bake.backend(), errors);
            requireText("NAV_BAKE_CONTENT_ID:" + bake.id(), bake.contentId(), errors);
            if (bake.revision() < 0) {
                errors.add("NAV_BAKE_REVISION_NEGATIVE:" + bake.id());
            }
            for (String surfaceId : bake.surfaceIds()) {
                WorldDefinition.Surface surface = surfaces.get(surfaceId);
                if (surface == null) {
                    errors.add("NAV_BAKE_SURFACE_UNKNOWN:" + bake.id() + ":" + surfaceId);
                } else if (!bake.floorId().equals(surface.floorId())) {
                    errors.add("NAV_BAKE_SURFACE_FLOOR_MISMATCH:" + bake.id() + ":" + surfaceId);
                }
            }
        }
    }

    private void validateAssets(Map<String, WorldDefinition.AssetReference> assets,
                                List<String> errors) {
        for (WorldDefinition.AssetReference asset : assets.values()) {
            requireText("ASSET_TYPE:" + asset.assetId(), asset.assetType(), errors);
            requireText("ASSET_CATALOG_ID:" + asset.assetId(), asset.catalogId(), errors);
            requireText("ASSET_VERSION:" + asset.assetId(), asset.version(), errors);
            if (looksLikeEnginePath(asset.assetId()) || looksLikeEnginePath(asset.catalogId())) {
                errors.add("ASSET_ENGINE_PATH_FORBIDDEN:" + asset.assetId());
            }
        }
    }

    private void validateBounds(String label, WorldDefinition.Bounds3 bounds, List<String> errors) {
        if (bounds == null || !bounds.isFiniteAndOrdered()) {
            errors.add(label + ":INVALID");
        }
    }

    private boolean validTransform(Transform3D transform) {
        if (transform == null || !finite(transform.position())) {
            return false;
        }
        Quaternion rotation = transform.rotation();
        return rotation != null && Double.isFinite(rotation.x()) && Double.isFinite(rotation.y())
                && Double.isFinite(rotation.z()) && Double.isFinite(rotation.w());
    }

    private boolean finite(Vec3 point) {
        return point != null && Double.isFinite(point.x())
                && Double.isFinite(point.y()) && Double.isFinite(point.z());
    }

    private boolean unitInterval(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    private boolean looksLikeEnginePath(String value) {
        if (blank(value)) {
            return false;
        }
        String lower = value.toLowerCase();
        return value.contains("/") || value.contains("\\") || value.contains(":")
                || lower.endsWith(".prefab") || lower.endsWith(".unity");
    }

    private void requireText(String label, String value, List<String> errors) {
        if (blank(value)) {
            errors.add(label + ":REQUIRED");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private <T> Map<String, T> index(String type,
                                     List<T> values,
                                     Function<T, String> idExtractor,
                                     List<String> errors) {
        Map<String, T> indexed = new LinkedHashMap<>();
        for (int i = 0; i < values.size(); i++) {
            T value = values.get(i);
            if (value == null) {
                errors.add(type + "_NULL:" + i);
                continue;
            }
            String id = idExtractor.apply(value);
            if (blank(id)) {
                errors.add(type + "_ID_REQUIRED:" + i);
                continue;
            }
            if (indexed.putIfAbsent(id, value) != null) {
                errors.add(type + "_ID_DUPLICATE:" + id);
            }
        }
        return indexed;
    }

    private <T> Map<String, T> indexWithoutErrors(List<T> values, Function<T, String> idExtractor) {
        Map<String, T> indexed = new LinkedHashMap<>();
        for (T value : values) {
            if (value != null && !blank(idExtractor.apply(value))) {
                indexed.putIfAbsent(idExtractor.apply(value), value);
            }
        }
        return indexed;
    }

    private List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }

    public record Result(boolean valid, List<String> errors, List<String> warnings) {
        public Result {
            errors = errors == null ? List.of() : List.copyOf(errors);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        private static Result invalid(List<String> errors, List<String> warnings) {
            return new Result(false, errors, warnings);
        }
    }
}
