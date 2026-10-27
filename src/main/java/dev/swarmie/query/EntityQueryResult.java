package dev.swarmie.query;

import java.util.List;

/** Result of a bounded entity-region query. */
public record EntityQueryResult(
        QueryStatus status,
        List<EntitySnapshot> entities,
        boolean complete,
        boolean truncated,
        String reason) {

    public EntityQueryResult {
        entities = List.copyOf(entities);
    }

    public boolean successful() {
        return status == QueryStatus.OK;
    }

    public static EntityQueryResult failure(QueryStatus status, String reason) {
        return new EntityQueryResult(status, List.of(), false, false, reason);
    }
}
