package dev.swarmie.interaction;

/** Structured result of one native interaction attempt. */
public record InteractionOutcome(boolean success, String message) {
    public static InteractionOutcome success(String message) {
        return new InteractionOutcome(true, message);
    }

    public static InteractionOutcome failure(String message) {
        return new InteractionOutcome(false, message);
    }
}
