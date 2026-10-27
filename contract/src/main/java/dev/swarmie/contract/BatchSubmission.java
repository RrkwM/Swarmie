package dev.swarmie.contract;

import java.util.List;

/** Best-effort batch; each item has its own acknowledgement and lifecycle. */
public record BatchSubmission(List<CommandSubmission> submissions) {
    public BatchSubmission {
        if (submissions == null || submissions.isEmpty()) {
            throw new IllegalArgumentException("batch must contain at least one submission");
        }
        if (submissions.size() > ContractLimits.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batch exceeds " + ContractLimits.MAX_BATCH_SIZE);
        }
        submissions = List.copyOf(submissions);
    }
}
