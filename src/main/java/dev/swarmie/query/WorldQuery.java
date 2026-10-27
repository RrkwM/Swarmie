package dev.swarmie.query;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * World-query capability. Distinct from local sensing: callers may explicitly
 * address arbitrary positions within world/chunk constraints.
 */
public interface WorldQuery {
    BlockQueryResult queryBlock(ServerLevel level, BlockPos pos);

    RegionQueryResult queryRegion(ServerLevel level, BlockPos from, BlockPos to);

    EntityQueryResult queryEntities(
            ServerLevel level, AABB box, Vec3 origin, EntityType<?> typeFilter, UUID exclude);
}
