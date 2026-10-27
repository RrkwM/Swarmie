package dev.swarmie.contract;

/** Minimal finite limits for the current contract. */
public final class ContractLimits {
    public static final int MAX_DURATION_TICKS = 6000;
    public static final int MAX_CORRELATION_ID_LENGTH = 128;
    public static final int MAX_BATCH_SIZE = 64;
    public static final int MAX_CANCEL_QUEUE = 64;
    public static final long IDEMPOTENCY_TTL_SECONDS = 600;

    private ContractLimits() {
    }
}
