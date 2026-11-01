package dev.swarmie.dcpu;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MotionDeviceTest {
    private static final class FakePort implements MotionPort {
        private long nextId = 1;
        private State state = State.RUNNING;
        private int error;
        private int submissions;
        private int stops;

        @Override
        public Submission submitTimedFlight(int vxMilli, int vyMilli, int vzMilli, int durationTicks) {
            this.submissions++;
            return Submission.accepted(this.nextId++);
        }

        @Override
        public Status status(long commandId) {
            return new Status(this.state, this.error);
        }

        @Override
        public void stop() {
            this.stops++;
        }
    }

    @Test
    void terminalStatusIsLatchedUntilAcknowledged() {
        FakePort port = new FakePort();
        AtomicInteger interrupts = new AtomicInteger();
        MotionDevice device = new MotionDevice(port, message -> interrupts.incrementAndGet());

        device.write(MotionDevice.DURATION, 20);
        device.write(MotionDevice.VX, 50);
        device.write(MotionDevice.CMD, MotionDevice.CMD_TIMED_FLIGHT);

        assertEquals(MotionDevice.BUSY, device.read(MotionDevice.STAT));
        assertEquals(1, port.submissions);
        assertEquals(0, interrupts.get());

        port.state = MotionPort.State.SUCCEEDED;
        device.tick();
        assertEquals(MotionDevice.SUCCEEDED, device.read(MotionDevice.STAT));
        assertEquals(1, interrupts.get(), "one terminal interrupt");

        device.tick();
        assertEquals(1, interrupts.get(), "terminal interrupt is not duplicated");
        device.write(MotionDevice.CMD, MotionDevice.CMD_TIMED_FLIGHT);
        assertEquals(MotionDevice.ERR_NOT_ACKNOWLEDGED, device.read(MotionDevice.ERR));

        device.write(MotionDevice.CMD, MotionDevice.CMD_ACK);
        assertEquals(MotionDevice.READY, device.read(MotionDevice.STAT));
        device.write(MotionDevice.CMD, MotionDevice.CMD_TIMED_FLIGHT);
        assertEquals(MotionDevice.BUSY, device.read(MotionDevice.STAT));
        assertEquals(2, port.submissions);
    }

    @Test
    void invalidArgumentsFaultAndQueueOneInterrupt() {
        FakePort port = new FakePort();
        AtomicInteger interrupts = new AtomicInteger();
        MotionDevice device = new MotionDevice(port, message -> interrupts.incrementAndGet());

        device.write(MotionDevice.DURATION, 0);
        device.write(MotionDevice.CMD, MotionDevice.CMD_TIMED_FLIGHT);

        assertEquals(MotionDevice.FAULTED, device.status());
        assertEquals(MotionDevice.ERR_INVALID_ARGUMENT, device.error());
        assertEquals(0, port.submissions);
        assertEquals(1, interrupts.get());
        device.tick();
        assertEquals(1, interrupts.get());
    }

    @Test
    void syntheticRuntimeFailureBecomesExplicitDeviceFailure() {
        FakePort port = new FakePort();
        AtomicInteger interrupts = new AtomicInteger();
        MotionDevice device = new MotionDevice(port, message -> interrupts.incrementAndGet());

        device.write(MotionDevice.DURATION, 5);
        device.write(MotionDevice.CMD, MotionDevice.CMD_TIMED_FLIGHT);
        port.state = MotionPort.State.FAILED;
        port.error = MotionDevice.ERR_RUNTIME_FAULT;
        device.tick();

        assertEquals(MotionDevice.FAILED, device.status());
        assertEquals(MotionDevice.ERR_RUNTIME_FAULT, device.error());
        assertEquals(1, interrupts.get());
    }

    @Test
    void stopAndCommandAcknowledgementAreExplicit() {
        FakePort port = new FakePort();
        MotionDevice device = new MotionDevice(port, message -> {
        });

        device.write(MotionDevice.CMD, MotionDevice.CMD_STOP);
        assertEquals(MotionDevice.READY, device.status());
        assertEquals(1, port.stops);

        device.write(MotionDevice.CMD, 0xffff);
        assertEquals(MotionDevice.FAULTED, device.status());
        assertEquals(MotionDevice.ERR_INVALID_COMMAND, device.error());
    }
}
