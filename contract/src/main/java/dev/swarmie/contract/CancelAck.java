package dev.swarmie.contract;

/** Acknowledgement of an asynchronous cancel-by-correlation request. */
public record CancelAck(
        RobotId robotId,
        String correlationId,
        CancelStatus status,
        CommandState state,
        ErrorCode error,
        String message) {

    public CancelAck {
        if (robotId == null || status == null) {
            throw new IllegalArgumentException("robotId and status are required");
        }
        message = message == null ? "" : message;
    }

    public static CancelAck accepted(RobotId robotId, String correlationId, CommandState state) {
        return new CancelAck(robotId, correlationId, CancelStatus.ACCEPTED, state, null,
                "cancellation queued for the server thread");
    }

    public static CancelAck rejected(
            RobotId robotId, String correlationId, ErrorCode error, String message) {
        return new CancelAck(robotId, correlationId, CancelStatus.REJECTED, null, error, message);
    }

    public static CancelAck alreadyTerminal(
            RobotId robotId, String correlationId, CommandState state) {
        return new CancelAck(robotId, correlationId, CancelStatus.ALREADY_TERMINAL, state, null,
                "command is already terminal");
    }

    public static CancelAck unknown(RobotId robotId, String correlationId) {
        return new CancelAck(robotId, correlationId, CancelStatus.UNKNOWN, null,
                ErrorCode.UNKNOWN_CORRELATION, "unknown correlationId");
    }
}
