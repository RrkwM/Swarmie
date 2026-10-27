package dev.swarmie.runtime;

/**
 * Immutable outcome of a submission attempt. A rejected submission never
 * enters the queue.
 */
public record SubmissionResult(boolean accepted, long commandId, String error) {
    public static SubmissionResult accepted(long commandId) {
        return new SubmissionResult(true, commandId, null);
    }

    public static SubmissionResult rejected(String error) {
        return new SubmissionResult(false, -1L, error);
    }
}
