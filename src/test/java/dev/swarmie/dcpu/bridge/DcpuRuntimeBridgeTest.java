package dev.swarmie.dcpu.bridge;

import dev.swarmie.dcpu.BasicMemoryBus;
import dev.swarmie.dcpu.ClockDevice;
import dev.swarmie.dcpu.Dcpu16;
import dev.swarmie.dcpu.DcpuFixtureProgram;
import dev.swarmie.dcpu.MotionDevice;
import dev.swarmie.dcpu.MotionPort;
import dev.swarmie.runtime.FlightTarget;
import dev.swarmie.runtime.SwarmieRuntime;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DcpuRuntimeBridgeTest {
    private static final class FakeTarget implements FlightTarget {
        private Vec3 velocity = Vec3.ZERO;
        private boolean removed;

        @Override
        public void setDesiredVelocity(Vec3 velocity) {
            this.velocity = velocity;
        }

        @Override
        public void stopFlight() {
            this.velocity = Vec3.ZERO;
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

    @Test
    void guestCommandReachesRuntimeAndTerminalUpdatesTheDevice() {
        FakeTarget target = new FakeTarget();
        SwarmieRuntime runtime = new SwarmieRuntime(target);
        RuntimeMotionPort port = new RuntimeMotionPort(runtime);
        BasicMemoryBus bus = new BasicMemoryBus();
        Dcpu16 cpu = new Dcpu16(bus);
        AtomicInteger interrupts = new AtomicInteger();
        MotionDevice motion = new MotionDevice(port, message -> {
            interrupts.incrementAndGet();
            cpu.queueInterrupt(message);
        });
        ClockDevice clock = new ClockDevice(() -> 77L,
                () -> switch (cpu.state()) {
                    case RUNNING -> 0;
                    case HALTED -> 1;
                    case FAULTED -> 2;
                },
                cpu::pendingInterrupts);
        bus.map(motion);
        bus.map(clock);
        DcpuFixtureProgram.load(cpu);

        cpu.run(200);
        assertEquals(MotionDevice.BUSY, motion.status());
        long commandId = motion.commandId();
        runtime.tick();
        assertEquals(0.05, target.velocity.x, 1.0E-9);

        for (int i = 0; i < 40; i++) {
            runtime.tick();
            motion.tick();
            cpu.run(40);
            if (motion.status() == MotionDevice.READY) {
                break;
            }
        }

        assertEquals(MotionPort.State.SUCCEEDED, port.status(commandId).state());
        assertEquals(MotionDevice.READY, motion.status());
        assertEquals(1, interrupts.get());
        assertEquals(DcpuFixtureProgram.HANDLER_MARKER_VALUE,
                bus.ram(DcpuFixtureProgram.HANDLER_MARKER));
        assertTrue(runtime.getCommand(commandId).isPresent());
    }
}
