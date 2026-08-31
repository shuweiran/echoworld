package com.roleplay.engine.simulation.worlddefinition;

import com.roleplay.engine.simulation.navigation.portal.PortalEndpoint;
import com.roleplay.engine.simulation.navigation.portal.PortalRuntimeState;
import com.roleplay.engine.simulation.navigation.portal.SemanticPortal;
import com.roleplay.engine.simulation.spatial.Quaternion;
import com.roleplay.engine.simulation.spatial.Transform3D;
import com.roleplay.engine.simulation.spatial.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldDefinitionValidatorTest {
    private final WorldDefinitionValidator validator = new WorldDefinitionValidator();

    @Test
    void acceptsCompleteEngineIndependentV2Definition() {
        WorldDefinitionValidator.Result result = validator.validate(validDefinition());

        assertTrue(result.valid(), () -> "unexpected errors: " + result.errors());
        assertTrue(result.errors().isEmpty());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void rejectsBrokenReferencesGeometryAndUnityAssetPathsTogether() {
        WorldDefinition valid = validDefinition();
        WorldDefinition invalid = new WorldDefinition(
                new WorldDefinition.Metadata("tavern", 1, "Tavern", 1),
                valid.floors(),
                List.of(new WorldDefinition.Surface("s1", "missing-floor",
                        WorldDefinition.SurfaceKind.FLOOR, bounds(0, 0, 0, 10, 3, 10),
                        "missing-bake", Set.of())),
                List.of(),
                List.of(),
                List.of(new SemanticPortal("door", SemanticPortal.Kind.DOOR,
                        endpoint("f1", "s1", 99, 0, 99),
                        endpoint("f2", "missing-surface", 0, 3, 0),
                        true, -1, "", Set.of())),
                List.of(new WorldDefinition.EntityDefinition("chair", "chair", "f1", "s1",
                        transform(2, 0, 2), "Assets/Chair.prefab", Set.of())),
                List.of(),
                List.of(),
                List.of(),
                List.of(new WorldDefinition.AssetReference(
                        "Assets/Chair.prefab", "furniture", "Assets/Catalog", "1")));

        WorldDefinitionValidator.Result result = validator.validate(invalid);

        assertFalse(result.valid());
        assertTrue(result.errors().contains("SCHEMA_VERSION_UNSUPPORTED:1"));
        assertTrue(result.errors().contains("SURFACE_FLOOR_UNKNOWN:s1"));
        assertTrue(result.errors().contains("SURFACE_NAV_BAKE_UNKNOWN:s1"));
        assertTrue(result.errors().contains("PORTAL_COST_INVALID:door"));
        assertTrue(result.errors().contains("PORTAL_DOOR_INTERACTION_REQUIRED:door"));
        assertTrue(result.errors().contains("PORTAL_SURFACE_UNKNOWN:door:B"));
        assertTrue(result.errors().contains("ASSET_ENGINE_PATH_FORBIDDEN:Assets/Chair.prefab"));
    }

    @Test
    void staticDefinitionAndRuntimeStateAreIndependentImmutableSnapshots() {
        List<WorldDefinition.Floor> mutableFloors = new ArrayList<>(validDefinition().floors());
        WorldDefinition source = validDefinition();
        WorldDefinition definition = new WorldDefinition(source.metadata(), mutableFloors,
                source.surfaces(), source.rooms(), source.zones(), source.portals(),
                source.entityDefinitions(), source.spawnPoints(), source.acousticRegions(),
                source.navigationBakeRefs(), source.assetRefs());
        mutableFloors.clear();

        Map<String, PortalRuntimeState> mutablePortalStates = new HashMap<>();
        mutablePortalStates.put("stairs", new PortalRuntimeState(
                "stairs", PortalRuntimeState.Availability.LOCKED, 4, "maintenance"));
        RuntimeWorldState runtime = new RuntimeWorldState("tavern", 20, Map.of(),
                mutablePortalStates, Map.of(), List.of());
        mutablePortalStates.clear();

        assertEquals(2, definition.floors().size());
        assertEquals(PortalRuntimeState.Availability.LOCKED,
                runtime.portalStates().get("stairs").availability());
        assertEquals(SemanticPortal.Kind.STAIRS, definition.portals().getFirst().kind());
        assertTrue(validator.validateRuntimeState(definition, runtime).valid());
    }

    @Test
    void runtimeValidationRejectsUnknownOrMismatchedDefinitions() {
        RuntimeWorldState runtime = new RuntimeWorldState("other-world", -1,
                Map.of("ghost", new RuntimeWorldState.EntityRuntimeState(
                        "missing", transform(0, 0, 0), true, -1)),
                Map.of("missing-portal", PortalRuntimeState.available("different-id")),
                Map.of(), List.of());

        WorldDefinitionValidator.Result result = validator.validateRuntimeState(validDefinition(), runtime);

        assertFalse(result.valid());
        assertTrue(result.errors().contains("RUNTIME_WORLD_DEFINITION_MISMATCH"));
        assertTrue(result.errors().contains("RUNTIME_TICK_NEGATIVE"));
        assertTrue(result.errors().contains("RUNTIME_ENTITY_DEFINITION_UNKNOWN:ghost"));
        assertTrue(result.errors().contains("RUNTIME_PORTAL_UNKNOWN:missing-portal"));
        assertTrue(result.errors().contains("RUNTIME_PORTAL_ID_MISMATCH:missing-portal"));
    }

    private static WorldDefinition validDefinition() {
        WorldDefinition.Bounds3 floor1Bounds = bounds(0, 0, 0, 20, 3, 20);
        WorldDefinition.Bounds3 floor2Bounds = bounds(0, 3, 0, 20, 6, 20);
        WorldDefinition.Surface surface1 = new WorldDefinition.Surface(
                "s1", "f1", WorldDefinition.SurfaceKind.FLOOR, floor1Bounds,
                "nav-f1", Set.of("walkable"));
        WorldDefinition.Surface surface2 = new WorldDefinition.Surface(
                "s2", "f2", WorldDefinition.SurfaceKind.FLOOR, floor2Bounds,
                "nav-f2", Set.of("walkable"));
        SemanticPortal stairs = new SemanticPortal("stairs", SemanticPortal.Kind.STAIRS,
                endpoint("f1", "s1", 10, 0, 10), endpoint("f2", "s2", 10, 3, 10),
                true, 3, "", Set.of("indoor"));
        return new WorldDefinition(
                new WorldDefinition.Metadata("tavern", WorldDefinition.SCHEMA_VERSION,
                        "Two-floor Tavern", 3),
                List.of(new WorldDefinition.Floor("f1", "Ground", 0, floor1Bounds),
                        new WorldDefinition.Floor("f2", "Upper", 3, floor2Bounds)),
                List.of(surface1, surface2),
                List.of(new WorldDefinition.Room("r1", "f1", List.of("s1"), List.of("z1"),
                        "acoustic-f1", bounds(0, 0, 0, 20, 3, 20), Set.of("public"))),
                List.of(new WorldDefinition.Zone("z1", "f1", "r1",
                        bounds(0, 0, 0, 5, 3, 5), Set.of("quiet"))),
                List.of(stairs),
                List.of(new WorldDefinition.EntityDefinition("chair-1", "chair", "f1", "s1",
                        transform(2, 0, 2), "chair.wood.03", Set.of("sittable"))),
                List.of(new WorldDefinition.SpawnPoint("spawn-player", "f1", "s1",
                        new Vec3(1, 0, 1), Set.of("player"))),
                List.of(new WorldDefinition.AcousticRegion("acoustic-f1", "f1", List.of("r1"),
                        0.3, 0.7, Set.of("indoor"))),
                List.of(new WorldDefinition.NavigationBakeReference("nav-f1", "f1", List.of("s1"),
                                "grid", "sha256-f1", 1),
                        new WorldDefinition.NavigationBakeReference("nav-f2", "f2", List.of("s2"),
                                "recast", "sha256-f2", 1)),
                List.of(new WorldDefinition.AssetReference(
                        "chair.wood.03", "furniture", "base-catalog", "1")));
    }

    private static WorldDefinition.Bounds3 bounds(double minX, double minY, double minZ,
                                                   double maxX, double maxY, double maxZ) {
        return new WorldDefinition.Bounds3(new Vec3(minX, minY, minZ),
                new Vec3(maxX, maxY, maxZ));
    }

    private static PortalEndpoint endpoint(String floor, String surface,
                                           double x, double y, double z) {
        return new PortalEndpoint(floor, surface, new Vec3(x, y, z));
    }

    private static Transform3D transform(double x, double y, double z) {
        return new Transform3D(new Vec3(x, y, z), Quaternion.identity());
    }
}
