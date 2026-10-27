package dev.swarmie.contract;

/** Result of submitting a command to the Adapter. */
public record SubmitAck(
        RobotId robotId,
        String correlationId,
        CommandType type,
        AckStatus status,
        String internalCommandId,
        CommandState state,
        ErrorCode error,
        String message) {

    public SubmitAck {
        if (robotId == null || type == null || status == null) {
            throw new IllegalArgumentException("robotId, type and status are required");
        }
        message = message == null ? "" : message;
    }

    public static SubmitAck accepted(
            RobotId robotId, String correlationId, CommandType type, String internalCommandId) {
        return new SubmitAck(robotId, correlationId, type, AckStatus.ACCEPTED,
                internalCommandId, CommandState.QUEUED, null, "");
    }

    public static SubmitAck duplicate(
            RobotId robotId, String correlationId, CommandType type,
            String internalCommandId, CommandState state, String message) {
        return new SubmitAck(robotId, correlationId, type, AckStatus.DUPLICATE,
                internalCommandId, state, null, message);
    }

    public static SubmitAck rejected(
            RobotId robotId, String correlationId, CommandType type, ErrorCode error, String message) {
        return new SubmitAck(robotId, correlationId, type, AckStatus.REJECTED,
                null, null, error, message);
    }
}
