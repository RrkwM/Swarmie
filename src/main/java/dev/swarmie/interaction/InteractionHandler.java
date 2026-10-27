package dev.swarmie.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Narrow interaction capability consumed by the Runtime. Implementations own
 * player-context and inventory handoff; the Runtime owns scheduling only.
 */
public interface InteractionHandler {
    InteractionOutcome mineBlock(BlockPos pos);

    InteractionOutcome useItemOn(BlockPos pos, Direction face, Vec3 hit);
}
