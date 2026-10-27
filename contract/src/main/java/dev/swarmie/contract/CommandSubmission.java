package dev.swarmie.contract;

/** One external submission with a caller-owned correlation key. */
public record CommandSubmission(RobotId robotId, String correlationId, CommandSpec command) {
    public CommandSubmission {
        if (robotId == null || command == null) {
            throw new IllegalArgumentException("robotId and command are required");
        }
        if (correlationId == null || correlationId.isBlank()
                || correlationId.length() > ContractLimits.MAX_CORRELATION_ID_LENGTH) {
            throw new IllegalArgumentException("correlationId must be 1.."
                    + ContractLimits.MAX_CORRELATION_ID_LENGTH + " characters");
        }
    }
}
