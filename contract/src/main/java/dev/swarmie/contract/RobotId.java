package dev.swarmie.contract;

import java.util.UUID;

/** Stable external robot identity. */
public record RobotId(UUID value) {
    public RobotId {
        if (value == null) {
            throw new IllegalArgumentException("robot UUID is required");
        }
    }

    public static RobotId parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("robot UUID string is required");
        }
        return new RobotId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return this.value.toString();
    }
}
