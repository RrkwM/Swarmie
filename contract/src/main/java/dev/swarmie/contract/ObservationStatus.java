package dev.swarmie.contract;

/** Outcome of one explicit block observation. Unknown is not air. */
public enum ObservationStatus {
    OK,
    UNLOADED,
    OUT_OF_BOUNDS,
    INVALID,
    UNSUPPORTED,
    ERROR
}
