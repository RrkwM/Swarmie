package dev.swarmie.dcpu;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DcpuFixtureProgramTest {
    private static final class FakePort implements MotionPort {
        private long nextId = 7;
        private State state = State.RUNNING;
        private int submissions;

        @Override
        public Submission submitTimedFlight(int vxMilli, int vyMilli, int vzMilli, int durationTicks) {
            assertEquals(20, durationTicks);
            assertEquals(50, vxMilli);
            this.submissions++;
            return Submission.accepted(this.nextId);
        }

        @Override
        public Status status(long commandId) {
            return new Status(this.state, 0);
        }

        @Override
        public void stop() {
        }
    }

    @Test
    void fixtureWritesMmioPollsClockAndHandlesInterrupt() {
        FakePort port = new FakePort();
        BasicMemoryBus bus = new BasicMemoryBus();
        Dcpu16 cpu = new Dcpu16(bus);
        AtomicInteger interrupts = new AtomicInteger();
        MotionDevice motion = new MotionDevice(port, message -> {
            interrupts.incrementAndGet();
            cpu.queueInterrupt(message);
        });
        ClockDevice clock = new ClockDevice(() -> 0x12345678L,
                () -> switch (cpu.state()) {
                    case RUNNING -> 0;
                    case HALTED -> 1;
                    case FAULTED -> 2;
                },
                cpu::pendingInterrupts);
        bus.map(motion);
        bus.map(clock);
        DcpuFixtureProgram.load(cpu);

        Dcpu16.Result boot = cpu.run(200);
        assertEquals(Dcpu16.State.RUNNING, boot.state());
        assertEquals(1, port.submissions);
        assertEquals(MotionDevice.BUSY, motion.status());

        port.state = MotionPort.State.SUCCEEDED;
        motion.tick();
        assertEquals(1, interrupts.get());
        cpu.run(200);

        assertEquals(MotionDevice.READY, motion.status());
        assertEquals(MotionDevice.ERR_NONE, motion.error());
        assertEquals(MotionDevice.SUCCEEDED,
                bus.ram(DcpuFixtureProgram.TERMINAL_STATUS));
        assertEquals(DcpuFixtureProgram.HANDLER_MARKER_VALUE,
                bus.ram(DcpuFixtureProgram.HANDLER_MARKER));
        assertEquals(0x5678, bus.ram(DcpuFixtureProgram.TICK_LOW));
    }

    @Test
    void experimentGateRequiresBothDevelopmentAndConfig() {
        assertFalse(ExperimentGate.enabled(false, true));
        assertFalse(ExperimentGate.enabled(true, false));
        assertFalse(ExperimentGate.enabled(false, false));
        assertTrue(ExperimentGate.enabled(true, true));
    }
}
