package dev.swarmie.contract;

/**
 * Immutable robot event. serverEpoch identifies the server session; sequence is
 * monotonically increasing within that epoch.
 */
public record RobotEvent(
        String eventId,
        long serverEpoch,
        long sequence,
        long serverTick,
        RobotEventType type,
        RobotId robotId,
        String correlationId,
        CommandResult commandResult,
        String detail) {

    public RobotEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (serverEpoch < 0 || sequence < 1 || serverTick < 0) {
            throw new IllegalArgumentException("invalid event epoch/sequence/tick");
        }
        if (type == null || robotId == null) {
            throw new IllegalArgumentException("event type and robotId are required");
        }
    }
}
