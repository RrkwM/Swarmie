package dev.swarmie.adapter;

import dev.swarmie.contract.RobotId;
import dev.swarmie.contract.RobotLifecycleState;
import dev.swarmie.contract.RobotState;
import dev.swarmie.contract.Vec3Dto;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RobotStateStoreTest {
    private RobotState state(UUID id, long tick) {
        return new RobotState(
                new RobotId(id), 7L, tick, "minecraft:overworld",
                new Vec3Dto(1, 2, 3), new Vec3Dto(0.1, 0, 0),
                90.0, 0.0, RobotLifecycleState.LOADED);
    }

    @Test
    void putFindReplaceAndRemove() {
        RobotStateStore store = new RobotStateStore();
        RobotId id = new RobotId(UUID.randomUUID());
        assertTrue(store.find(id).isEmpty());

        store.put(state(id.value(), 1));
        assertEquals(1, store.find(id).orElseThrow().serverTick());

        store.put(state(id.value(), 2));
        assertEquals(2, store.find(id).orElseThrow().serverTick());

        store.remove(id);
        assertTrue(store.find(id).isEmpty());
    }

    @Test
    void retainOnlyDropsUnloadedRobots() {
        RobotStateStore store = new RobotStateStore();
        RobotId kept = new RobotId(UUID.randomUUID());
        RobotId dropped = new RobotId(UUID.randomUUID());
        store.put(state(kept.value(), 1));
        store.put(state(dropped.value(), 1));

        store.retainOnly(Set.of(kept.value()));
        assertTrue(store.find(kept).isPresent());
        assertTrue(store.find(dropped).isEmpty());
    }
}
