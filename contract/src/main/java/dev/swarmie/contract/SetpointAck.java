package dev.swarmie.contract;

/**
 * Acknowledgement of a velocity-setpoint request.
 *
 * <p>Acceptance only means the server accepted the target. It does not mean the
 * robot has reached the target velocity. A rejected request does not change the
 * current target or its revision.
 */
public record SetpointAck(
        RobotId robotId,
        String channel,
        long revision,
        boolean active,
        boolean accepted,
        Vec3Dto effectiveVelocity,
        boolean clamped,
        ErrorCode error,
        String message,
        long serverEpoch,
        long serverTick) {

    public static final String VELOCITY_CHANNEL = "velocity";

    public SetpointAck {
        if (robotId == null || channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("robotId and channel are required");
        }
        if (revision < 0 || serverEpoch < 0 || serverTick < 0) {
            throw new IllegalArgumentException("revision, serverEpoch and serverTick must be non-negative");
        }
        message = message == null ? "" : message;
        if (accepted && error != null) {
            throw new IllegalArgumentException("accepted setpoint must not carry an error");
        }
        if (!accepted && error == null) {
            throw new IllegalArgumentException("rejected setpoint must carry an error");
        }
        if (accepted && active && effectiveVelocity == null) {
            throw new IllegalArgumentException("an active setpoint needs an effective velocity");
        }
    }

    public static SetpointAck accepted(
            RobotId robotId, long revision, Vec3Dto effectiveVelocity, boolean clamped,
            long serverEpoch, long serverTick) {
        return new SetpointAck(robotId, VELOCITY_CHANNEL, revision, true, true,
                effectiveVelocity, clamped, null, "", serverEpoch, serverTick);
    }

    public static SetpointAck cleared(
            RobotId robotId, long revision, long serverEpoch, long serverTick) {
        return new SetpointAck(robotId, VELOCITY_CHANNEL, revision, false, true,
                null, false, null, "velocity setpoint cleared", serverEpoch, serverTick);
    }

    public static SetpointAck rejected(
            RobotId robotId, long revision, boolean active, ErrorCode error, String message,
            long serverEpoch, long serverTick) {
        return new SetpointAck(robotId, VELOCITY_CHANNEL, revision, active, false,
                null, false, error, message, serverEpoch, serverTick);
    }
}
