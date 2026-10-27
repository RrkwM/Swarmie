package dev.swarmie.query;

import net.minecraft.core.BlockPos;

import java.util.List;

/** Result of a bounded axis-aligned block-region query. */
public record RegionQueryResult(
        QueryStatus status,
        BlockPos from,
        BlockPos to,
        List<BlockSnapshot> blocks,
        List<BlockPos> unavailable,
        boolean complete,
        int requestedVolume,
        String reason) {

    public RegionQueryResult {
        blocks = List.copyOf(blocks);
        unavailable = List.copyOf(unavailable);
    }

    public boolean successful() {
        return status == QueryStatus.OK;
    }

    public static RegionQueryResult failure(QueryStatus status, BlockPos from, BlockPos to, String reason) {
        return new RegionQueryResult(status, from, to, List.of(), List.of(), false, 0, reason);
    }
}
