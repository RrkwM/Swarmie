package dev.swarmie.runtime;

/** Expected command failure with a user-facing reason (not a programming error). */
final class CommandFailureException extends RuntimeException {
    CommandFailureException(String message) {
        super(message);
    }
}
