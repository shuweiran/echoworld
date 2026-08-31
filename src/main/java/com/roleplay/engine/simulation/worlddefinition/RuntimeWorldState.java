package com.roleplay.engine.simulation.worlddefinition;

import com.roleplay.engine.simulation.navigation.portal.PortalRuntimeState;
import com.roleplay.engine.simulation.spatial.Transform3D;

import java.util.List;
import java.util.Map;

/**
 * Mutable facts projected as an immutable tick snapshot. It references a static
 * {@link WorldDefinition} by id and never owns floors, rooms, surfaces or assets.
 */
public record RuntimeWorldState(String worldDefinitionId,
                                long tick,
                                Map<String, EntityRuntimeState> entityStates,
                                Map<String, PortalRuntimeState> portalStates,
                                Map<String, InventoryRuntimeState> inventoryStates,
                                List<TransientEvent> transientEvents) {
    public RuntimeWorldState {
        entityStates = entityStates == null ? Map.of() : Map.copyOf(entityStates);
        portalStates = portalStates == null ? Map.of() : Map.copyOf(portalStates);
        inventoryStates = inventoryStates == null ? Map.of() : Map.copyOf(inventoryStates);
        transientEvents = transientEvents == null ? List.of() : List.copyOf(transientEvents);
    }

    public record EntityRuntimeState(String definitionId,
                                     Transform3D transform,
                                     boolean active,
                                     long revision) { }

    public record InventoryRuntimeState(List<String> itemIds, long revision) {
        public InventoryRuntimeState {
            itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
        }
    }

    public record TransientEvent(String type, long tick, Map<String, Object> payload) {
        public TransientEvent {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }
}
