package dev.swarmie.adapter;

import dev.swarmie.contract.InventoryState;
import dev.swarmie.contract.RobotId;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Volatile snapshots of loaded robot inventories, refreshed on the server tick. */
final class RobotInventoryStore {
    private volatile Map<UUID, InventoryState> inventories = Map.of();

    void put(InventoryState inventory) {
        Map<UUID, InventoryState> next = new HashMap<>(this.inventories);
        next.put(inventory.robotId().value(), inventory);
        this.inventories = Map.copyOf(next);
    }

    void remove(RobotId robotId) {
        if (!this.inventories.containsKey(robotId.value())) {
            return;
        }
        Map<UUID, InventoryState> next = new HashMap<>(this.inventories);
        next.remove(robotId.value());
        this.inventories = Map.copyOf(next);
    }

    void retainOnly(Set<UUID> retained) {
        Map<UUID, InventoryState> next = new HashMap<>(this.inventories);
        next.keySet().retainAll(retained);
        this.inventories = Map.copyOf(next);
    }

    Optional<InventoryState> find(RobotId robotId) {
        return Optional.ofNullable(this.inventories.get(robotId.value()));
    }
}
