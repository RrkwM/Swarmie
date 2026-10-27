package dev.swarmie.contract;

import java.util.Set;

/** Immutable last-known robot metadata exposed by the Adapter registry. */
public record RobotDescriptor(
        RobotId robotId,
        String dimensionId,
        Vec3Dto position,
        RobotLifecycleState lifecycle,
        Set<CommandType> supportedCommands,
        long lastUpdatedTick) {

    public RobotDescriptor {
        if (robotId == null || dimensionId == null || dimensionId.isBlank()
                || position == null || lifecycle == null) {
            throw new IllegalArgumentException("robotId, dimension, position and lifecycle are required");
        }
        supportedCommands = supportedCommands == null ? Set.of() : Set.copyOf(supportedCommands);
        if (lastUpdatedTick < 0) {
            throw new IllegalArgumentException("lastUpdatedTick must be non-negative");
        }
    }
}
