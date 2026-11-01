package dev.swarmie.contract;

/**
 * One inventory slot summary. {@code itemId} is null only for an empty slot;
 * item components and raw stacks are deliberately not exposed.
 */
public record InventorySlot(int slot, String itemId, int count) {
    public InventorySlot {
        if (slot < 0 || slot >= InventoryState.SLOT_COUNT) {
            throw new IllegalArgumentException("slot must be in 0.." + (InventoryState.SLOT_COUNT - 1));
        }
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative");
        }
        if (itemId == null) {
            if (count != 0) {
                throw new IllegalArgumentException("an empty slot must have count 0");
            }
        }
        else if (itemId.isBlank() || count == 0) {
            throw new IllegalArgumentException("a non-empty slot needs an item id and positive count");
        }
    }

    public boolean empty() {
        return this.itemId == null;
    }
}
