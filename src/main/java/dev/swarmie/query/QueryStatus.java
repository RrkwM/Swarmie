package dev.swarmie.query;

/** Explicit query outcome; air is OK, not a failure. */
public enum QueryStatus {
    OK,
    UNLOADED,
    OUT_OF_BOUNDS,
    INVALID,
    WRONG_THREAD,
    UNSUPPORTED,
    ERROR
}
