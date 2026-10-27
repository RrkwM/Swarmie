package dev.swarmie.contract;

import java.util.List;

/**
 * Ordered slice of the event store. missed=true means the requested cursor was
 * older than the oldest retained event; events starts at oldestAvailableSequence.
 */
public record EventBatch(
        long serverEpoch,
        long afterSequence,
        long oldestAvailableSequence,
        long latestSequence,
        long nextSequence,
        boolean missed,
        List<RobotEvent> events) {

    public EventBatch {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
