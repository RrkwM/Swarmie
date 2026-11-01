package dev.swarmie.adapter;

import dev.swarmie.contract.ContractLimits;
import dev.swarmie.contract.ErrorCode;
import dev.swarmie.contract.RobotId;
import dev.swarmie.contract.SetpointAck;
import dev.swarmie.contract.Vec3Dto;
import dev.swarmie.runtime.FlightTarget;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-robot velocity-setpoint state and the single motion-channel owner.
 *
 * <p>Setpoints and legacy {@code TIMED_FLIGHT}/{@code STOP} commands must not
 * both drive the flight controller. This class makes that exclusion atomic
 * across the JSONL thread (setpoint requests) and the server thread (legacy
 * command submission/terminal handling).
 */
final class VelocitySetpoints {
    private record Target(Vec3Dto velocity, long revision) {
    }

    record Frame(boolean clear, Vec3Dto velocity) {
    }

    private final Map<UUID, Target> targets = new HashMap<>();
    private final Map<UUID, Long> revisions = new HashMap<>();
    private final Set<UUID> clearPending = new HashSet<>();
    private final Map<UUID, Integer> legacyMotion = new HashMap<>();

    synchronized SetpointAck set(RobotId robotId, Vec3Dto requested, long serverEpoch, long serverTick) {
        UUID id = robotId.value();
        long revision = revisions.getOrDefault(id, 0L);
        if (legacyMotion.getOrDefault(id, 0) > 0) {
            return SetpointAck.rejected(robotId, revision, false,
                    ErrorCode.MOTION_CHANNEL_BUSY,
                    "legacy TimedFlight/Stop owns the motion channel; wait for it to finish",
                    serverEpoch, serverTick);
        }

        double length = length(requested);
        boolean clamped = length > ContractLimits.MAX_SETPOINT_SPEED;
        Vec3Dto effective = clamped ? scale(requested, ContractLimits.MAX_SETPOINT_SPEED / length) : requested;
        long nextRevision = revision + 1L;
        this.revisions.put(id, nextRevision);
        this.targets.put(id, new Target(effective, nextRevision));
        this.clearPending.remove(id);
        return SetpointAck.accepted(robotId, nextRevision, effective, clamped, serverEpoch, serverTick);
    }

    /** Test/local convenience; production callers pass the session epoch. */
    synchronized SetpointAck set(RobotId robotId, Vec3Dto requested, long serverTick) {
        return set(robotId, requested, 0L, serverTick);
    }

    synchronized SetpointAck clear(RobotId robotId, long serverEpoch, long serverTick) {
        UUID id = robotId.value();
        long revision = this.revisions.getOrDefault(id, 0L);
        if (this.legacyMotion.getOrDefault(id, 0) > 0) {
            return SetpointAck.rejected(robotId, revision, false,
                    ErrorCode.MOTION_CHANNEL_BUSY,
                    "legacy TimedFlight/Stop owns the motion channel; wait for it to finish",
                    serverEpoch, serverTick);
        }
        long nextRevision = revision + 1L;
        this.revisions.put(id, nextRevision);
        this.targets.remove(id);
        this.clearPending.add(id);
        return SetpointAck.cleared(robotId, nextRevision, serverEpoch, serverTick);
    }

    /** Test/local convenience; production callers pass the session epoch. */
    synchronized SetpointAck clear(RobotId robotId, long serverTick) {
        return clear(robotId, 0L, serverTick);
    }

    /**
     * Claims motion for a legacy command unless a setpoint currently owns it.
     *
     * @return false when the setpoint channel is active
     */
    synchronized boolean claimLegacy(RobotId robotId) {
        UUID id = robotId.value();
        if (this.targets.containsKey(id)) {
            return false;
        }
        this.legacyMotion.merge(id, 1, Integer::sum);
        return true;
    }

    synchronized void releaseLegacy(RobotId robotId) {
        UUID id = robotId.value();
        int remaining = this.legacyMotion.getOrDefault(id, 0) - 1;
        if (remaining <= 0) {
            this.legacyMotion.remove(id);
        }
        else {
            this.legacyMotion.put(id, remaining);
        }
    }

    synchronized long revision(RobotId robotId) {
        return this.revisions.getOrDefault(robotId.value(), 0L);
    }

    synchronized boolean isSetpointActive(RobotId robotId) {
        return this.targets.containsKey(robotId.value());
    }

    synchronized boolean hasLegacyMotion(RobotId robotId) {
        return this.legacyMotion.getOrDefault(robotId.value(), 0) > 0;
    }

    /**
     * Consumes a pending clear and returns the latest target. Called on the
     * server thread once per tick.
     */
    synchronized Frame frame(UUID robotId) {
        boolean clear = this.clearPending.remove(robotId);
        Target target = this.targets.get(robotId);
        return new Frame(clear, target == null ? null : target.velocity());
    }

    /** Forgets all motion state on unload, removal or dimension change. */
    synchronized void forget(UUID robotId) {
        this.targets.remove(robotId);
        this.revisions.remove(robotId);
        this.clearPending.remove(robotId);
        this.legacyMotion.remove(robotId);
    }

    /**
     * Forgets setpoint state and stops the controller so a reused entity cannot
     * resume the old target after reload. Lifecycle cleanup must not leave the
     * flight controller's desired velocity behind.
     */
    synchronized void forget(UUID robotId, FlightTarget target) {
        forget(robotId);
        if (target != null) {
            target.stopFlight();
        }
    }

    private static double length(Vec3Dto vector) {
        return Math.sqrt(vector.x() * vector.x() + vector.y() * vector.y() + vector.z() * vector.z());
    }

    private static Vec3Dto scale(Vec3Dto vector, double factor) {
        return new Vec3Dto(vector.x() * factor, vector.y() * factor, vector.z() * factor);
    }
}
