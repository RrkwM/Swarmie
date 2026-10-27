package dev.swarmie.query;

import net.minecraft.core.BlockPos;

/** Result of a single absolute block query. */
public record BlockQueryResult(QueryStatus status, BlockPos pos, BlockSnapshot block, String reason) {
    public boolean successful() {
        return status == QueryStatus.OK;
    }

    public static BlockQueryResult ok(BlockPos pos, BlockSnapshot block) {
        return new BlockQueryResult(QueryStatus.OK, pos, block, "");
    }

    public static BlockQueryResult failure(QueryStatus status, BlockPos pos, String reason) {
        return new BlockQueryResult(status, pos, null, reason);
    }
}
