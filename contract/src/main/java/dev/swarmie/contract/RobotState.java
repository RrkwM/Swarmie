package dev.swarmie.contract;

/**
 * One server-authoritative self-state snapshot. This is live state, not the
 * registry metadata in {@link RobotDescriptor}; {@code serverTick} is its
 * freshness indicator within {@code serverEpoch}.
 */
public record RobotState(
        RobotId robotId,
        long serverEpoch,
        long serverTick,
        String dimensionId,
        Vec3Dto position,
        Vec3Dto velocity,
        double yaw,
        double pitch,
        RobotLifecycleState lifecycle) {

    public RobotState {
        if (robotId == null || position == null || velocity == null || lifecycle == null) {
            throw new IllegalArgumentException("robotId, position, velocity and lifecycle are required");
        }
        if (serverEpoch < 0 || serverTick < 0) {
            throw new IllegalArgumentException("serverEpoch and serverTick must be non-negative");
        }
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("dimensionId is required");
        }
        if (!Double.isFinite(yaw) || !Double.isFinite(pitch)) {
            throw new IllegalArgumentException("yaw and pitch must be finite");
        }
    }
}
