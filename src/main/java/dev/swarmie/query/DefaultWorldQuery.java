package dev.swarmie.query;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Default server-world query implementation; read-only and chunk-safe. */
public final class DefaultWorldQuery implements WorldQuery {
    public static final int MAX_REGION_VOLUME = 4096;
    public static final int MAX_ENTITY_RESULTS = 256;
    public static final int MAX_ENTITY_QUERY_VOLUME = 65536;

    @Override
    public BlockQueryResult queryBlock(ServerLevel level, BlockPos pos) {
        QueryStatus status = validateLevel(level);
        if (status != null) {
            return BlockQueryResult.failure(status, pos, "world query requires the server thread");
        }
        if (pos == null) {
            return BlockQueryResult.failure(QueryStatus.INVALID, null, "block position is required");
        }
        if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) {
            return BlockQueryResult.failure(QueryStatus.OUT_OF_BOUNDS, pos, "position is outside the world");
        }
        if (!level.isLoaded(pos)) {
            return BlockQueryResult.failure(QueryStatus.UNLOADED, pos, "chunk is not loaded");
        }

        BlockState state = level.getBlockState(pos);
        return BlockQueryResult.ok(pos, BlockSnapshot.of(pos, state));
    }

    @Override
    public RegionQueryResult queryRegion(ServerLevel level, BlockPos from, BlockPos to) {
        QueryStatus status = validateLevel(level);
        if (status != null) {
            return RegionQueryResult.failure(status, from, to, "world query requires the server thread");
        }
        if (from == null || to == null) {
            return RegionQueryResult.failure(QueryStatus.INVALID, from, to, "both region corners are required");
        }

        int minX = Math.min(from.getX(), to.getX());
        int minY = Math.min(from.getY(), to.getY());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxX = Math.max(from.getX(), to.getX());
        int maxY = Math.max(from.getY(), to.getY());
        int maxZ = Math.max(from.getZ(), to.getZ());

        long volume = regionVolume(from, to);
        if (volume <= 0 || volume > MAX_REGION_VOLUME) {
            return RegionQueryResult.failure(QueryStatus.INVALID, from, to,
                    "region volume must be between 1 and " + MAX_REGION_VOLUME);
        }

        List<BlockSnapshot> blocks = new ArrayList<>((int) volume);
        List<BlockPos> unavailable = new ArrayList<>();

        for (long x = minX; x <= maxX; x++) {
            for (long y = minY; y <= maxY; y++) {
                for (long z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos((int) x, (int) y, (int) z);
                    if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) {
                        unavailable.add(pos);
                        continue;
                    }
                    if (!level.isLoaded(pos)) {
                        unavailable.add(pos);
                        continue;
                    }
                    blocks.add(BlockSnapshot.of(pos, level.getBlockState(pos)));
                }
            }
        }

        boolean complete = unavailable.isEmpty();
        return new RegionQueryResult(
                QueryStatus.OK, from, to, blocks, unavailable, complete, (int) volume,
                complete ? "" : "some positions are outside loaded/world bounds");
    }

    @Override
    public EntityQueryResult queryEntities(
            ServerLevel level, AABB box, Vec3 origin, EntityType<?> typeFilter, UUID exclude) {
        QueryStatus status = validateLevel(level);
        if (status != null) {
            return EntityQueryResult.failure(status, "world query requires the server thread");
        }
        if (box == null || origin == null) {
            return EntityQueryResult.failure(QueryStatus.INVALID, "entity box and origin are required");
        }
        if (!isFinite(box.minX) || !isFinite(box.minY) || !isFinite(box.minZ)
                || !isFinite(box.maxX) || !isFinite(box.maxY) || !isFinite(box.maxZ)
                || box.maxX < box.minX || box.maxY < box.minY || box.maxZ < box.minZ) {
            return EntityQueryResult.failure(QueryStatus.INVALID, "entity box is invalid");
        }

        long dx = (long) Math.ceil(box.maxX - box.minX);
        long dy = (long) Math.ceil(box.maxY - box.minY);
        long dz = (long) Math.ceil(box.maxZ - box.minZ);
        long volume = volumeOrZero(dx, dy, dz);
        if (volume <= 0 || volume > MAX_ENTITY_QUERY_VOLUME) {
            return EntityQueryResult.failure(QueryStatus.INVALID,
                    "entity box volume must be between 1 and " + MAX_ENTITY_QUERY_VOLUME);
        }

        List<Entity> found = level.getEntities((Entity) null, box, entity ->
                (typeFilter == null || entity.getType() == typeFilter)
                        && (exclude == null || !entity.getUUID().equals(exclude)));

        List<EntitySnapshot> snapshots = new ArrayList<>(found.size());
        for (Entity entity : found) {
            Identifier typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (typeId == null) {
                typeId = Identifier.fromNamespaceAndPath("minecraft", "unknown");
            }
            snapshots.add(new EntitySnapshot(
                    typeId,
                    entity.getUUID(),
                    entity.position(),
                    entity.getBoundingBox(),
                    entity.position().distanceTo(origin)));
        }
        snapshots.sort(Comparator.comparingDouble(EntitySnapshot::distanceToOrigin)
                .thenComparing(snapshot -> snapshot.uuid().toString()));

        boolean truncated = snapshots.size() > MAX_ENTITY_RESULTS;
        if (truncated) {
            snapshots = new ArrayList<>(snapshots.subList(0, MAX_ENTITY_RESULTS));
        }
        boolean complete = chunksLoaded(level, box);
        return new EntityQueryResult(
                QueryStatus.OK, snapshots, complete, truncated,
                complete ? "" : "entity box spans unloaded chunks");
    }

    /** Inclusive volume; 0 means invalid or overflow. */
    public static long regionVolume(BlockPos from, BlockPos to) {
        if (from == null || to == null) {
            return 0L;
        }
        long dx = (long) Math.max(from.getX(), to.getX()) - Math.min(from.getX(), to.getX()) + 1L;
        long dy = (long) Math.max(from.getY(), to.getY()) - Math.min(from.getY(), to.getY()) + 1L;
        long dz = (long) Math.max(from.getZ(), to.getZ()) - Math.min(from.getZ(), to.getZ()) + 1L;
        return volumeOrZero(dx, dy, dz);
    }

    private static QueryStatus validateLevel(ServerLevel level) {
        if (level == null) {
            return QueryStatus.INVALID;
        }
        MinecraftServer server = level.getServer();
        if (server == null || !server.isSameThread()) {
            return QueryStatus.WRONG_THREAD;
        }
        return null;
    }

    private static long volumeOrZero(long dx, long dy, long dz) {
        if (dx <= 0 || dy <= 0 || dz <= 0) {
            return 0L;
        }
        if (dx > Integer.MAX_VALUE || dy > Integer.MAX_VALUE || dz > Integer.MAX_VALUE) {
            return 0L;
        }
        if (dx * dy > Integer.MAX_VALUE / dz) {
            return 0L;
        }
        return dx * dy * dz;
    }

    private static boolean chunksLoaded(ServerLevel level, AABB box) {
        int minCX = Mth.floor(box.minX) >> 4;
        int maxCX = Mth.floor(box.maxX) >> 4;
        int minCZ = Mth.floor(box.minZ) >> 4;
        int maxCZ = Mth.floor(box.maxZ) >> 4;
        int y = level.getMinY();

        for (long cx = minCX; cx <= maxCX; cx++) {
            for (long cz = minCZ; cz <= maxCZ; cz++) {
                if (!level.isLoaded(new BlockPos((int) (cx << 4), y, (int) (cz << 4)))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isFinite(double value) {
        return Double.isFinite(value);
    }
}
