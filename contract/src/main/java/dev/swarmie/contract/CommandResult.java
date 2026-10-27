package dev.swarmie.contract;

/** Command result; no query/sensor payload is part of the current contract. */
public record CommandResult(
        RobotId robotId,
        String correlationId,
        String internalCommandId,
        CommandState state,
        ErrorCode error,
        String message) {

    public CommandResult {
        if (robotId == null || state == null) {
            throw new IllegalArgumentException("robotId and state are required");
        }
        message = message == null ? "" : message;
    }
}
