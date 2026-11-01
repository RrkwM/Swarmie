package dev.swarmie.contract;

import java.util.ArrayList;
import java.util.List;

/** Stable nine-slot inventory snapshot with a server-tick freshness point. */
public record InventoryState(
        RobotId robotId,
        long serverEpoch,
        long serverTick,
        int selectedSlot,
        List<InventorySlot> slots) {

    public static final int SLOT_COUNT = 9;

    public InventoryState {
        if (robotId == null) {
            throw new IllegalArgumentException("robotId is required");
        }
        if (serverEpoch < 0 || serverTick < 0) {
            throw new IllegalArgumentException("serverEpoch and serverTick must be non-negative");
        }
        if (selectedSlot < 0 || selectedSlot >= SLOT_COUNT) {
            throw new IllegalArgumentException("selectedSlot must be in 0.." + (SLOT_COUNT - 1));
        }
        if (slots == null || slots.size() != SLOT_COUNT) {
            throw new IllegalArgumentException("inventory must contain exactly " + SLOT_COUNT + " slots");
        }
        List<InventorySlot> ordered = new ArrayList<>(slots);
        for (int i = 0; i < ordered.size(); i++) {
            InventorySlot slot = ordered.get(i);
            if (slot == null || slot.slot() != i) {
                throw new IllegalArgumentException("inventory slots must be ordered 0.." + (SLOT_COUNT - 1));
            }
        }
        slots = List.copyOf(ordered);
    }
}
