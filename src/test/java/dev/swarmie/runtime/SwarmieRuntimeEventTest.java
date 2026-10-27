package dev.swarmie.runtime;

import dev.swarmie.SwarmieFlightController;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SwarmieRuntimeEventTest {
    @Test
    void acceptedStartedAndTerminalCarryCorrelation() {
        FakeTarget target = new FakeTarget();
        SwarmieRuntime runtime = new SwarmieRuntime(target);
        RecordingListener listener = new RecordingListener();
        runtime.setListener(listener);

        long id = runtime.submitStop("corr-stop").commandId();
        assertEquals(1, listener.accepted.size());
        assertEquals("corr-stop", listener.accepted.get(0).getCorrelationId());
        assertEquals(0, listener.started.size());

        runtime.tick();
        assertEquals(1, listener.started.size());
        assertEquals(id, listener.started.get(0).getId());
        assertEquals(0, listener.terminal.size());

        runtime.tick();
        assertEquals(1, listener.terminal.size());
        assertEquals(SwarmieCommand.State.SUCCEEDED, listener.terminal.get(0).getState());
        assertEquals("corr-stop", listener.terminal.get(0).getCorrelationId());
        assertEquals(1, listener.terminalCount);
    }

    @Test
    void unloadInterruptsActiveAndQueuedWithTerminalEvents() {
        FakeTarget target = new FakeTarget();
        SwarmieRuntime runtime = new SwarmieRuntime(target);
        RecordingListener listener = new RecordingListener();
        runtime.setListener(listener);

        long active = runtime.submitTimedFlight(new Vec3(0.2, 0, 0), 100, "active").commandId();
        long queued = runtime.submitTimedFlight(new Vec3(0.2, 0, 0), 100, "queued").commandId();
        runtime.tick();
        runtime.onOwnerUnloaded("robot unloaded");

        assertEquals(2, listener.terminal.size());
        assertEquals(SwarmieCommand.State.INTERRUPTED, listener.terminal.get(0).getState());
        assertEquals("active", listener.terminal.get(0).getCorrelationId());
        assertEquals(SwarmieCommand.State.INTERRUPTED, listener.terminal.get(1).getState());
        assertEquals("queued", listener.terminal.get(1).getCorrelationId());
        assertEquals(2, listener.terminalCount);
        assertTrue(runtime.isTerminated());
        assertEquals(0, runtime.getQueuedCommands().size());
        assertTrue(runtime.getCurrentCommand().isEmpty());
        assertTrue(runtime.getHistory().stream().anyMatch(c -> c.getId() == active));
        assertTrue(runtime.getHistory().stream().anyMatch(c -> c.getId() == queued));
    }

    @Test
    void queueFullDoesNotNotifyAccepted() {
        FakeTarget target = new FakeTarget();
        SwarmieRuntime runtime = new SwarmieRuntime(target);
        RecordingListener listener = new RecordingListener();
        runtime.setListener(listener);
        for (int i = 0; i < SwarmieRuntime.MAX_QUEUE_SIZE; i++) {
            assertTrue(runtime.submitStop("c" + i).accepted());
        }
        assertEquals(SwarmieRuntime.MAX_QUEUE_SIZE, listener.accepted.size());
        assertFalse(runtime.submitStop("overflow").accepted());
        assertEquals(SwarmieRuntime.MAX_QUEUE_SIZE, listener.accepted.size());
    }

    @Test
    void cancellationDoesNotDuplicateTerminalEvents() {
        FakeTarget target = new FakeTarget();
        SwarmieRuntime runtime = new SwarmieRuntime(target);
        RecordingListener listener = new RecordingListener();
        runtime.setListener(listener);

        long active = runtime.submitTimedFlight(new Vec3(0.2, 0, 0), 100, "active").commandId();
        runtime.tick();
        assertTrue(runtime.cancel(active));
        assertEquals(1, listener.terminalCount);
        assertEquals(SwarmieCommand.State.CANCELLED, listener.terminal.get(0).getState());
        runtime.tick();
        assertEquals(1, listener.terminalCount);
    }

    private static final class RecordingListener implements SwarmieRuntime.Listener {
        private final List<SwarmieCommand> accepted = new ArrayList<>();
        private final List<SwarmieCommand> started = new ArrayList<>();
        private final List<SwarmieCommand> terminal = new ArrayList<>();
        private int terminalCount;

        @Override
        public void onCommandAccepted(SwarmieCommand command) {
            this.accepted.add(command);
        }

        @Override
        public void onCommandStarted(SwarmieCommand command) {
            this.started.add(command);
        }

        @Override
        public void onCommandTerminal(SwarmieCommand command) {
            this.terminal.add(command);
            this.terminalCount++;
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
    }
}
