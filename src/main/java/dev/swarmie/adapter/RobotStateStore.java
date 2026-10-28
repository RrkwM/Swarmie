package dev.swarmie.adapter;

import dev.swarmie.contract.RobotId;
import dev.swarmie.contract.RobotState;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Immutable, volatile snapshot store for server-authoritative self-state. */
final class RobotStateStore {
    private volatile Map<UUID, RobotState> states = Map.of();

    void put(RobotState state) {
        Map<UUID, RobotState> next = new HashMap<>(this.states);
        next.put(state.robotId().value(), state);
        this.states = Map.copyOf(next);
    }

    void remove(RobotId robotId) {
        if (!this.states.containsKey(robotId.value())) {
            return;
        }
        Map<UUID, RobotState> next = new HashMap<>(this.states);
        next.remove(robotId.value());
        this.states = Map.copyOf(next);
    }

    void retainOnly(Set<UUID> retained) {
        Map<UUID, RobotState> next = new HashMap<>(this.states);
        next.keySet().retainAll(retained);
        this.states = Map.copyOf(next);
    }

    Optional<RobotState> find(RobotId robotId) {
        return Optional.ofNullable(this.states.get(robotId.value()));
    }
}
