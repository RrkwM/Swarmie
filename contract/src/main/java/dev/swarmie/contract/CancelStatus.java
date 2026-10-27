package dev.swarmie.contract;

/** Result of a cancellation request. */
public enum CancelStatus {
    ACCEPTED,
    REJECTED,
    ALREADY_TERMINAL,
    UNKNOWN
}
