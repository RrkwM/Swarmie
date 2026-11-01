package dev.swarmie.adapter;

import dev.swarmie.contract.InventorySlot;
import dev.swarmie.contract.InventoryState;
import dev.swarmie.contract.RobotId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RobotInventoryStoreTest {
    private InventoryState inventory(RobotId id, long tick) {
        List<InventorySlot> slots = new ArrayList<>();
        for (int i = 0; i < InventoryState.SLOT_COUNT; i++) {
            slots.add(new InventorySlot(i, null, 0));
        }
        return new InventoryState(id, 7L, tick, 0, slots);
    }

    @Test
    void putFindReplaceRemoveAndRetain() {
        RobotInventoryStore store = new RobotInventoryStore();
        RobotId kept = new RobotId(UUID.randomUUID());
        RobotId dropped = new RobotId(UUID.randomUUID());
        assertTrue(store.find(kept).isEmpty());

        store.put(inventory(kept, 1));
        assertEquals(1, store.find(kept).orElseThrow().serverTick());
        store.put(inventory(kept, 2));
        assertEquals(2, store.find(kept).orElseThrow().serverTick());

        store.put(inventory(dropped, 1));
        store.retainOnly(Set.of(kept.value()));
        assertTrue(store.find(kept).isPresent());
        assertTrue(store.find(dropped).isEmpty());

        store.remove(kept);
        assertTrue(store.find(kept).isEmpty());
    }

    @Test
    void snapshotsDoNotExposeMutableSlotLists() {
        RobotId id = new RobotId(UUID.randomUUID());
        List<InventorySlot> source = new ArrayList<>();
        for (int i = 0; i < InventoryState.SLOT_COUNT; i++) {
            source.add(new InventorySlot(i, null, 0));
        }
        InventoryState state = new InventoryState(id, 7L, 1L, 0, source);
        source.clear();
        assertEquals(InventoryState.SLOT_COUNT, state.slots().size());
        assertThrows(UnsupportedOperationException.class, () -> state.slots().clear());
    }
}
