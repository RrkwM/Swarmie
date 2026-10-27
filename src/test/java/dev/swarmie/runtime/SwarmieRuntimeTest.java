package dev.swarmie.runtime;

import dev.swarmie.SwarmieFlightController;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SwarmieRuntimeTest {
    @Test
    void commandsRunInFifoOrder() {
        FakeTarget target = new FakeTarget();
        SwarmieRuntime runtime = new SwarmieRuntime(target);

        SubmissionResult first = runtime.submitTimedFlight(new Vec3(0.1, 0, 0), 3);
        SubmissionResult second = runtime.submitStop();
        assertTrue(first.accepted());
        assertTrue(second.accepted());
        assertTrue(runtime.getCurrentCommand().isEmpty());
        assertEquals(2, runtime.getQueuedCommands().size());

        step(runtime, target);
        assertEquals(first.commandId(), runtime.getCurrentCommand().orElseThrow().getId());

        int ticks = 0;
        while ((!runtime.getCurrentCommand().isEmpty() || !runtime.getQueuedCommands().isEmpty()) && ticks++ < 300) {
            step(runtime, target);
        }
        assertTrue(ticks < 300);
        List<SwarmieCommand> history = runtime.getHistory();
        assertEquals(2, history.size());
        assertEquals(first.commandId(), history.get(0).getId());
        assertEquals(second.commandId(), history.get(1).getId());
        assertEquals(SwarmieCommand.State.SUCCEEDED, history.get(0).getState());
        assertEquals(SwarmieCommand.State.SUCCEEDED, history.get(1).getState());
    }

    @Test
    void activeAndQueuedCancellationAreDistinct() {
        FakeTarget target = new FakeTarget();
        SwarmieRuntime runtime = new SwarmieRuntime(target);

        long active = runtime.submitTimedFlight(new Vec3(0.2, 0, 0), 100).commandId();
        step(runtime, target);
        assertTrue(runtime.cancel(active));
        assertTrue(runtime.getCurrentCommand().isEmpty());
        assertEquals(SwarmieCommand.State.CANCELLED, runtime.getCommand(active).orElseThrow().getState());
        assertTrue(target.stopRequests > 0);

        long queued = runtime.submitTimedFlight(new Vec3(0.2, 0, 0), 100).commandId();
        long queuedStop = runtime.submitStop().commandId();
        assertTrue(runtime.cancel(queuedStop));
        assertEquals(SwarmieCommand.State.CANCELLED, runtime.getCommand(queuedStop).orElseThrow().getState());
        step(runtime, target);
        assertEquals(queued, runtime.getCurrentCommand().orElseThrow().getId());
    }

    @Test
    void cancellationAllAndRecoveryWork() {
        FakeTarget target = new FakeTarget();
        SwarmieRuntime runtime = new SwarmieRuntime(target);
        runtime.submitStop();
        runtime.submitStop();
        runtime.submitStop();
        step(runtime, target);
        assertEquals(3, runtime.cancelAll());
        assertTrue(runtime.getCurrentCommand().isEmpty());
        assertTrue(runtime.getQueuedCommands().isEmpty());

        long next = runtime.submitStop().commandId();
        step(runtime, target);
        step(runtime, target);
        assertEquals(SwarmieCommand.State.SUCCEEDED, runtime.getCommand(next).orElseThrow().getState());
    }

    @Test
    void queueCapacityAndOwnerRemovalAreExplicit() {
        FakeTarget target = new FakeTarget();
        SwarmieRuntime runtime = new SwarmieRuntime(target);
        for (int i = 0; i < SwarmieRuntime.MAX_QUEUE_SIZE; i++) {
            assertTrue(runtime.submitTimedFlight(new Vec3(0.1, 0, 0), 10).accepted());
        }
        SubmissionResult overflow = runtime.submitStop();
        assertFalse(overflow.accepted());
        assertTrue(overflow.error().contains("full"));

        target.removed = true;
        step(runtime, target);
        assertTrue(runtime.isTerminated());
        assertTrue(runtime.getCurrentCommand().isEmpty());
        assertTrue(runtime.getQueuedCommands().isEmpty());
    }

    @Test
    void invalidSubmissionsAreRejected() {
        FakeTarget target = new FakeTarget();
        SwarmieRuntime runtime = new SwarmieRuntime(target);
        assertFalse(runtime.submitTimedFlight(new Vec3(Double.NaN, 0, 0), 1).accepted());
        assertFalse(runtime.submitTimedFlight(Vec3.ZERO, 0).accepted());
        assertFalse(runtime.submitTimedFlight(Vec3.ZERO, SwarmieRuntime.MAX_DURATION_TICKS + 1).accepted());
        assertFalse(runtime.submitMineBlock(BlockPos.ZERO).accepted());
    }

    private static void step(SwarmieRuntime runtime, FakeTarget target) {
        runtime.tick();
        target.tick();
    }

    private static final class FakeTarget implements FlightTarget {
        private final SwarmieFlightController controller = new SwarmieFlightController();
        private Vec3 velocity = Vec3.ZERO;
        private boolean removed;
        private int stopRequests;

        @Override
        public void setDesiredVelocity(Vec3 velocity) {
            this.controller.setDesiredVelocity(velocity);
        }

        @Override
        public void stopFlight() {
            this.stopRequests++;
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
