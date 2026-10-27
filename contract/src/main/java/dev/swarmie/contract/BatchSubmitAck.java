package dev.swarmie.contract;

import java.util.List;

/** Per-item batch acknowledgements; the batch is not atomic. */
public record BatchSubmitAck(List<SubmitAck> acks) {
    public BatchSubmitAck {
        acks = acks == null ? List.of() : List.copyOf(acks);
    }
}
