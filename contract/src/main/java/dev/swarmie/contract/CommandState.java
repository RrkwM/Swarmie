package dev.swarmie.contract;

/** Lifecycle state of a command. */
public enum CommandState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == INTERRUPTED;
    }
}
