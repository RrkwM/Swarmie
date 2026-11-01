package dev.swarmie.contract;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ObservationContractTest {
    private final RobotId robot = new RobotId(UUID.randomUUID());

    private InventoryState inventory(int selected, String itemId) {
        List<InventorySlot> slots = new ArrayList<>();
        for (int i = 0; i < InventoryState.SLOT_COUNT; i++) {
            slots.add(i == selected
                    ? new InventorySlot(i, itemId, 3)
                    : new InventorySlot(i, null, 0));
        }
        return new InventoryState(robot, 7L, 11L, selected, slots);
    }

    @Test
    void inventoryRequiresNineOrderedSlotsAndAValidSelection() {
        InventoryState valid = inventory(2, "minecraft:iron_hoe");
        assertEquals(9, valid.slots().size());
        assertEquals("minecraft:iron_hoe", valid.slots().get(2).itemId());
        assertTrue(valid.slots().get(0).empty());

        List<InventorySlot> wrongOrder = new ArrayList<>(valid.slots());
        wrongOrder.set(0, new InventorySlot(1, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new InventoryState(robot, 7L, 11L, 0, wrongOrder));
        assertThrows(IllegalArgumentException.class,
                () -> new InventoryState(robot, 7L, 11L, 9, valid.slots()));
        assertThrows(IllegalArgumentException.class,
                () -> new InventoryState(robot, 7L, 11L, 0, valid.slots().subList(0, 8)));
        assertThrows(IllegalArgumentException.class,
                () -> new InventorySlot(0, null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new InventorySlot(0, "minecraft:stone", 0));
    }

    @Test
    void blockObservationSeparatesUnknownFromAir() {
        BlockPosDto pos = new BlockPosDto(1, 64, -3);
        BlockSnapshotDto air = new BlockSnapshotDto(
                pos, "minecraft:air", Map.of(), true);
        BlockObservation observed = new BlockObservation(
                robot, pos, ObservationStatus.OK, air, "", 7L, 11L);
        assertTrue(observed.block().air());

        BlockObservation unloaded = new BlockObservation(
                robot, pos, ObservationStatus.UNLOADED, null, "chunk is not loaded", 7L, 11L);
        assertNull(unloaded.block());
        assertEquals(ObservationStatus.UNLOADED, unloaded.status());

        assertThrows(IllegalArgumentException.class,
                () -> new BlockObservation(robot, pos, ObservationStatus.OK, null, "", 7L, 11L));
        assertThrows(IllegalArgumentException.class,
                () -> new BlockObservation(robot, pos, ObservationStatus.UNLOADED, air, "", 7L, 11L));
    }

    @Test
    void blockPropertySummaryIsACopy() {
        Map<String, String> source = new java.util.HashMap<>();
        source.put("age", "3");
        BlockSnapshotDto snapshot = new BlockSnapshotDto(
                new BlockPosDto(0, 64, 0), "minecraft:wheat", source, false);
        source.put("age", "7");
        assertEquals("3", snapshot.properties().get("age"));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.properties().put("age", "9"));
    }
}
