package dev.swarmie.interaction;

import dev.swarmie.SwarmieFlightController;
import dev.swarmie.inventory.SwarmieInventory;
import dev.swarmie.runtime.FlightTarget;
import dev.swarmie.runtime.SwarmieCommand;
import dev.swarmie.runtime.SwarmieRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InteractionRuntimeTest {
    @Test
    void inventorySlotsAndCountsAreBounded() {
        SwarmieInventory inventory = new SwarmieInventory();
        assertEquals(9, inventory.getContainerSize());
        assertTrue(inventory.isEmpty());

        inventory.setSelectedSlot(-5);
        assertEquals(0, inventory.getSelectedSlot());
        inventory.setSelectedSlot(99);
        assertEquals(8, inventory.getSelectedSlot());

        inventory.setItem(0, ItemStack.EMPTY);
        assertTrue(inventory.getItem(0).isEmpty());
        assertTrue(inventory.insertItem(ItemStack.EMPTY).isEmpty());
        assertThrows(IndexOutOfBoundsException.class, () -> inventory.setItem(9, ItemStack.EMPTY));
    }

    @Test
    void mineSuccessBecomesTerminalOnNextTick() {
        FakeTarget target = new FakeTarget();
        FakeHandler handler = new FakeHandler();
        SwarmieRuntime runtime = new SwarmieRuntime(target, handler);

        long id = runtime.submitMineBlock(new BlockPos(1, 2, 3)).commandId();
        step(runtime, target);
        assertEquals(1, handler.mineCalls);
        assertEquals(SwarmieCommand.State.RUNNING, runtime.getCurrentCommand().orElseThrow().getState());
        step(runtime, target);
        assertEquals(SwarmieCommand.State.SUCCEEDED, runtime.getCommand(id).orElseThrow().getState());
    }

    @Test
    void mineFailureDoesNotStallRuntime() {
        FakeTarget target = new FakeTarget();
        FakeHandler handler = new FakeHandler();
        handler.mineSuccess = false;
        SwarmieRuntime runtime = new SwarmieRuntime(target, handler);

        long failed = runtime.submitMineBlock(BlockPos.ZERO).commandId();
        step(runtime, target);
        assertEquals(SwarmieCommand.State.FAILED, runtime.getCommand(failed).orElseThrow().getState());
        assertTrue(runtime.getCommand(failed).orElseThrow().getResult().contains("protected"));

        long next = runtime.submitStop().commandId();
        step(runtime, target);
        step(runtime, target);
        assertEquals(SwarmieCommand.State.SUCCEEDED, runtime.getCommand(next).orElseThrow().getState());
    }

    @Test
    void queuedInteractionCanBeCancelledBeforeExecution() {
        FakeTarget target = new FakeTarget();
        FakeHandler handler = new FakeHandler();
        SwarmieRuntime runtime = new SwarmieRuntime(target, handler);
        long mine = runtime.submitMineBlock(BlockPos.ZERO).commandId();
        long use = runtime.submitUseItemOn(BlockPos.ZERO, Direction.UP, new Vec3(0.5, 1, 0.5)).commandId();
        assertTrue(runtime.cancel(use));
        step(runtime, target);
        assertEquals(mine, runtime.getCurrentCommand().orElseThrow().getId());
        assertEquals(1, handler.mineCalls);
    }

    @Test
    void missingHandlerRejectsInteraction() {
        FakeTarget target = new FakeTarget();
        SwarmieRuntime runtime = new SwarmieRuntime(target);
        assertFalse(runtime.submitMineBlock(BlockPos.ZERO).accepted());
        assertFalse(runtime.submitUseItemOn(BlockPos.ZERO, Direction.UP, Vec3.ZERO).accepted());
    }

    private static void step(SwarmieRuntime runtime, FakeTarget target) {
        runtime.tick();
        target.tick();
    }

    private static final class FakeHandler implements InteractionHandler {
        private boolean mineSuccess = true;
        private int mineCalls;

        @Override
        public InteractionOutcome mineBlock(BlockPos pos) {
            this.mineCalls++;
            return this.mineSuccess
                    ? InteractionOutcome.success("block destroyed")
                    : InteractionOutcome.failure("block is protected");
        }

        @Override
        public InteractionOutcome useItemOn(BlockPos pos, Direction face, Vec3 hit) {
            return InteractionOutcome.success("item used");
        }
    }

    private static final class FakeTarget implements FlightTarget {
        private final SwarmieFlightController controller = new SwarmieFlightController();
        private Vec3 velocity = Vec3.ZERO;
        private boolean removed;

        @Override
        public void setDesiredVelocity(Vec3 velocity) {
            this.controller.setDesiredVelocity(velocity);
        }

        @Override
        public void stopFlight() {
            this.controller.stop();
        }

        @Override
        public Vec3 getVelocity() {
            return this.velocity;
        }

        @Override
        public boolean isRemoved() {
            return this.removed;
        }

        private void tick() {
            this.velocity = this.controller.update(this.velocity);
        }
    }
}
