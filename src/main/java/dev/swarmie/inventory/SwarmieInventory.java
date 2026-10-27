package dev.swarmie.inventory;

import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nine-slot robot-owned inventory. The robot inventory is authoritative; it is
 * never a view over a player inventory.
 */
public final class SwarmieInventory implements Container {
    public static final int SIZE = 9;

    private static final Logger LOGGER = LoggerFactory.getLogger(SwarmieInventory.class);

    private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    private int selectedSlot;

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : this.items) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        checkSlot(slot);
        return this.items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        checkSlot(slot);
        ItemStack stack = this.items.get(slot);
        if (stack.isEmpty() || amount <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack removed = stack.split(amount);
        if (stack.isEmpty()) {
            this.items.set(slot, ItemStack.EMPTY);
        }
        this.setChanged();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        checkSlot(slot);
        ItemStack stack = this.items.get(slot);
        this.items.set(slot, ItemStack.EMPTY);
        return stack;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        checkSlot(slot);
        ItemStack copy = stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
        if (!copy.isEmpty()) {
            int max = Math.max(1, copy.getItem().getDefaultMaxStackSize());
            if (copy.getCount() > max) {
                copy.setCount(max);
            }
        }
        this.items.set(slot, copy);
        this.setChanged();
    }

    @Override
    public void setChanged() {
        // No block entity / no container listeners at this gate.
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < SIZE; i++) {
            this.items.set(i, ItemStack.EMPTY);
        }
    }

    public NonNullList<ItemStack> getItems() {
        return this.items;
    }

    public int getSelectedSlot() {
        return this.selectedSlot;
    }

    public void setSelectedSlot(int slot) {
        this.selectedSlot = Math.max(0, Math.min(SIZE - 1, slot));
    }

    public ItemStack getSelectedStack() {
        return this.items.get(this.selectedSlot);
    }

    /**
     * Inserts a copy, merging into matching stacks first, then empty slots.
     *
     * @return the remainder that did not fit (possibly empty)
     */
    public ItemStack insertItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack remaining = stack.copy();
        int max = Math.max(1, remaining.getItem().getDefaultMaxStackSize());

        for (int i = 0; i < SIZE && !remaining.isEmpty(); i++) {
            ItemStack existing = this.items.get(i);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, remaining)) {
                continue;
            }
            int room = max - existing.getCount();
            if (room <= 0) {
                continue;
            }
            int move = Math.min(room, remaining.getCount());
            existing.setCount(existing.getCount() + move);
            remaining.setCount(remaining.getCount() - move);
            this.setChanged();
        }

        for (int i = 0; i < SIZE && !remaining.isEmpty(); i++) {
            if (!this.items.get(i).isEmpty()) {
                continue;
            }
            int move = Math.min(max, remaining.getCount());
            this.items.set(i, remaining.copyWithCount(move));
            remaining.setCount(remaining.getCount() - move);
            this.setChanged();
        }

        return remaining;
    }

    public void save(ValueOutput output) {
        ContainerHelper.saveAllItems(output, this.items);
        output.putInt("SelectedSlot", this.selectedSlot);
    }

    public void load(ValueInput input) {
        this.clearContent();
        this.selectedSlot = 0;

        try {
            NonNullList<ItemStack> loaded = NonNullList.withSize(SIZE, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(input, loaded);
            for (int i = 0; i < SIZE; i++) {
                this.items.set(i, sanitize(loaded.get(i)));
            }
            this.selectedSlot = Math.max(0, Math.min(SIZE - 1, input.getIntOr("SelectedSlot", 0)));
        }
        catch (RuntimeException exception) {
            LOGGER.warn("Failed to load Swarmie inventory; resetting to empty", exception);
            this.clearContent();
            this.selectedSlot = 0;
        }
    }

    private static ItemStack sanitize(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getCount() <= 0) {
            return ItemStack.EMPTY;
        }
        int max = Math.max(1, stack.getItem().getDefaultMaxStackSize());
        if (stack.getCount() > max) {
            stack.setCount(max);
        }
        return stack;
    }

    private static void checkSlot(int slot) {
        if (slot < 0 || slot >= SIZE) {
            throw new IndexOutOfBoundsException("Swarmie inventory slot " + slot + " outside 0.." + (SIZE - 1));
        }
    }
}
