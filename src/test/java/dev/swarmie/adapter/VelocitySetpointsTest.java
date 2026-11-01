package dev.swarmie.adapter;

import dev.swarmie.contract.ErrorCode;
import dev.swarmie.contract.RobotId;
import dev.swarmie.contract.SetpointAck;
import dev.swarmie.contract.Vec3Dto;
import dev.swarmie.runtime.FlightTarget;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VelocitySetpointsTest {
    private final RobotId robot = new RobotId(UUID.randomUUID());

    @Test
    void setReplacesPreviousTargetAndClampsToContractLimit() {
        VelocitySetpoints setpoints = new VelocitySetpoints();

        SetpointAck first = setpoints.set(robot, new Vec3Dto(1.0, 0, 0), 10);
        assertTrue(first.accepted());
        assertTrue(first.active());
        assertTrue(first.clamped());
        assertEquals(1, first.revision());
        assertEquals(0.35, first.effectiveVelocity().x(), 1.0E-9);

        SetpointAck second = setpoints.set(robot, new Vec3Dto(0.1, 0.2, 0), 11);
        assertTrue(second.accepted());
        assertFalse(second.clamped());
        assertEquals(2, second.revision());
        assertEquals(0.1, second.effectiveVelocity().x(), 1.0E-9);

        VelocitySetpoints.Frame frame = setpoints.frame(robot.value());
        assertFalse(frame.clear());
        assertEquals(0.1, frame.velocity().x(), 1.0E-9);
        assertEquals(0.2, frame.velocity().y(), 1.0E-9);

        // The target is persistent: reading the frame does not consume it.
        VelocitySetpoints.Frame again = setpoints.frame(robot.value());
        assertFalse(again.clear());
        assertEquals(0.2, again.velocity().y(), 1.0E-9);
    }

    @Test
    void clearIsConsumedOnceAndRejectsNoTarget() {
        VelocitySetpoints setpoints = new VelocitySetpoints();
        setpoints.set(robot, new Vec3Dto(0.1, 0, 0), 1);

        SetpointAck clear = setpoints.clear(robot, 2);
        assertTrue(clear.accepted());
        assertFalse(clear.active());
        assertEquals(2, clear.revision());

        VelocitySetpoints.Frame first = setpoints.frame(robot.value());
        assertTrue(first.clear());
        assertNull(first.velocity());

        VelocitySetpoints.Frame second = setpoints.frame(robot.value());
        assertFalse(second.clear());
        assertNull(second.velocity());
    }

    @Test
    void legacyMotionAndSetpointCannotBothOwnMotion() {
        VelocitySetpoints setpoints = new VelocitySetpoints();

        assertTrue(setpoints.claimLegacy(robot));
        SetpointAck rejected = setpoints.set(robot, new Vec3Dto(0.1, 0, 0), 1);
        assertFalse(rejected.accepted());
        assertEquals(ErrorCode.MOTION_CHANNEL_BUSY, rejected.error());
        assertEquals(0, rejected.revision());

        setpoints.releaseLegacy(robot);
        assertTrue(setpoints.set(robot, new Vec3Dto(0.1, 0, 0), 2).accepted());
        assertFalse(setpoints.claimLegacy(robot));

        setpoints.clear(robot, 3);
        assertTrue(setpoints.claimLegacy(robot));

        // A clear cannot steal the channel back from an active legacy command.
        assertFalse(setpoints.clear(robot, 4).accepted());
    }

    @Test
    void legacyOwnershipIsCountedAcrossBatchSubmissions() {
        VelocitySetpoints setpoints = new VelocitySetpoints();
        assertTrue(setpoints.claimLegacy(robot));
        assertTrue(setpoints.claimLegacy(robot));

        setpoints.releaseLegacy(robot); // one rejected request does not release the other
        assertFalse(setpoints.set(robot, new Vec3Dto(0.1, 0, 0), 1).accepted());
        setpoints.releaseLegacy(robot);
        assertTrue(setpoints.set(robot, new Vec3Dto(0.1, 0, 0), 2).accepted());
    }

    @Test
    void rejectedRequestDoesNotAdvanceRevision() {
        VelocitySetpoints setpoints = new VelocitySetpoints();
        assertTrue(setpoints.claimLegacy(robot));
        setpoints.set(robot, new Vec3Dto(0, 0, 0), 1);
        setpoints.releaseLegacy(robot);

        SetpointAck accepted = setpoints.set(robot, new Vec3Dto(0, 0, 0), 2);
        assertTrue(accepted.accepted());
        assertEquals(1, accepted.revision());
        assertTrue(accepted.active());
        assertEquals(0, accepted.effectiveVelocity().x(), 1.0E-9);
    }

    @Test
    void forgetDropsTargetAndOwnership() {
        VelocitySetpoints setpoints = new VelocitySetpoints();
        setpoints.set(robot, new Vec3Dto(0.1, 0, 0), 1);
        setpoints.forget(robot.value());

        VelocitySetpoints.Frame frame = setpoints.frame(robot.value());
        assertFalse(frame.clear());
        assertNull(frame.velocity());
        assertEquals(0, setpoints.revision(robot));
        assertTrue(setpoints.claimLegacy(robot));
    }

    @Test
    void forgetStopsLifecycleMotion() {
        VelocitySetpoints setpoints = new VelocitySetpoints();
        setpoints.set(robot, new Vec3Dto(0.1, 0, 0), 1);
        FakeTarget target = new FakeTarget();

        setpoints.forget(robot.value(), target);

        assertTrue(target.stopped, "lifecycle cleanup must stop the old controller target");
        assertNull(setpoints.frame(robot.value()).velocity());
    }

    private static final class FakeTarget implements FlightTarget {
        private boolean stopped;

        @Override
        public void setDesiredVelocity(Vec3 velocity) {
        }

        @Override
        public void stopFlight() {
            this.stopped = true;
        }

        @Override
        public Vec3 getVelocity() {
            return Vec3.ZERO;
        }

        @Override
        public boolean isRemoved() {
            return false;
        }
    }
}
