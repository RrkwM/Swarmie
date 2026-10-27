package dev.swarmie.sensor;

import dev.swarmie.SwarmieEntity;
import dev.swarmie.query.BlockSnapshot;
import dev.swarmie.query.EntitySnapshot;
import dev.swarmie.query.QueryStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Bounded forward sensor; geometry is deterministic and unit-testable. */
public final class DefaultLocalSensor implements LocalSensor {
    private static final double EPSILON = 1.0E-9D;

    @Override
    public SensorObservation observe(SwarmieEntity robot, SensorConfig config) {
        if (robot == null || config == null) {
            return SensorObservation.error(QueryStatus.INVALID, null, null, config,
                    "robot and config are required");
        }
        if (robot.isRemoved()) {
            return SensorObservation.error(QueryStatus.ERROR, robot.position(), Vec3.ZERO, config,
                    "robot is removed");
        }
        if (!(robot.level() instanceof ServerLevel level)) {
            return SensorObservation.error(QueryStatus.INVALID, robot.position(), Vec3.ZERO, config,
                    "robot is not on a server level");
        }

        Vec3 origin = robot.position();
        Vec3 direction = robot.getViewVector(1.0F);
        UUID excludeEntity = robot.getUUID();
        if (!isFinite(origin) || !isFinite(direction)) {
            return SensorObservation.error(QueryStatus.INVALID, origin, direction, config,
                    "origin and direction must be finite");
        }
        if (direction.lengthSqr() < EPSILON) {
            return SensorObservation.error(QueryStatus.INVALID, origin, direction, config,
                    "direction must not be zero");
        }
        MinecraftServer server = level.getServer();
        if (server == null || !server.isSameThread()) {
            return SensorObservation.error(QueryStatus.WRONG_THREAD, origin, direction, config,
                    "local sensing requires the server thread");
        }
        if (config.lineOfSight()) {
            return SensorObservation.error(QueryStatus.UNSUPPORTED, origin, direction, config,
                    "line-of-sight filtering is not supported in this build");
        }

        Vec3 view = direction.normalize();
        double range = config.range();
        AABB box = new AABB(
                origin.x - range, origin.y - range, origin.z - range,
                origin.x + range, origin.y + range, origin.z + range);

        List<Hit> hits = new ArrayList<>();
        boolean incomplete = false;

        int minX = Mth.floor(box.minX);
        int minY = Mth.floor(box.minY);
        int minZ = Mth.floor(box.minZ);
        int maxX = Mth.floor(box.maxX);
        int maxY = Mth.floor(box.maxY);
        int maxZ = Mth.floor(box.maxZ);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) {
                        incomplete = true;
                        continue;
                    }
                    if (!level.isLoaded(pos)) {
                        incomplete = true;
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    Vec3 center = Vec3.atCenterOf(pos);
                    Vec3 relative = center.subtract(origin);
                    double distance = relative.length();
                    if (!withinRange(relative, range)) {
                        continue;
                    }
                    if (!withinCone(view, relative, config.fovDegrees())) {
                        continue;
                    }
                    hits.add(Hit.block(new SensorBlock(BlockSnapshot.of(pos, state), relative, distance), distance));
                }
            }
        }

        List<Entity> entities = level.getEntities((Entity) null, box, entity ->
                excludeEntity == null || !entity.getUUID().equals(excludeEntity));
        for (Entity entity : entities) {
            Vec3 relative = entity.position().subtract(origin);
            double distance = relative.length();
            if (!withinRange(relative, range)) {
                continue;
            }
            if (!withinCone(view, relative, config.fovDegrees())) {
                continue;
            }
            Identifier typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (typeId == null) {
                typeId = Identifier.fromNamespaceAndPath("minecraft", "unknown");
            }
            EntitySnapshot snapshot = new EntitySnapshot(
                    typeId, entity.getUUID(), entity.position(), entity.getBoundingBox(), distance);
            hits.add(Hit.entity(new SensorEntity(snapshot, relative, distance,
                    angleDegrees(view, relative)), distance));
        }

        if (!chunksLoaded(level, box)) {
            incomplete = true;
        }

        hits.sort(Comparator.comparingDouble(Hit::distance)
                .thenComparing(hit -> hit.block() != null ? hit.block().block().blockId().toString()
                        : hit.entity().entity().typeId().toString()));

        boolean truncated = exceedsLimit(hits.size(), config.maxResults());
        hits = limit(hits, config.maxResults());

        List<SensorBlock> blocks = new ArrayList<>();
        List<SensorEntity> detectedEntities = new ArrayList<>();
        for (Hit hit : hits) {
            if (hit.block() != null) {
                blocks.add(hit.block());
            } else {
                detectedEntities.add(hit.entity());
            }
        }

        return new SensorObservation(
                QueryStatus.OK, origin, view, config, blocks, detectedEntities, truncated, incomplete,
                incomplete ? "some scanned chunks/positions are unavailable" : "");
    }

    /** Angle between a normalized view and a relative vector, in degrees. */
    public static double angleDegrees(Vec3 view, Vec3 relative) {
        double denominator = view.length() * relative.length();
        if (denominator < EPSILON) {
            return 180.0D;
        }
        double dot = view.dot(relative) / denominator;
        dot = Math.max(-1.0D, Math.min(1.0D, dot));
        return Math.toDegrees(Math.acos(dot));
    }

    /** Full-angle FOV check around the view direction. */
    public static boolean withinCone(Vec3 view, Vec3 relative, double fovDegrees) {
        double denominator = view.length() * relative.length();
        if (denominator < EPSILON) {
            return false;
        }
        double dot = view.dot(relative) / denominator;
        double halfAngleCos = Math.cos(Math.toRadians(fovDegrees * 0.5D));
        return dot >= halfAngleCos - EPSILON;
    }

    /** Inclusive maximum-range check; zero-length is not a valid detection. */
    public static boolean withinRange(Vec3 relative, double range) {
        double distance = relative.length();
        return distance >= EPSILON && distance <= range;
    }

    /** Pure result-limit helper used by sensing and tests. */
    public static <T> List<T> limit(List<T> sorted, int maxResults) {
        if (maxResults <= 0 || sorted.isEmpty()) {
            return List.of();
        }
        if (sorted.size() <= maxResults) {
            return List.copyOf(sorted);
        }
        return List.copyOf(sorted.subList(0, maxResults));
    }

    /** True when a result set needs truncation. */
    public static boolean exceedsLimit(int total, int maxResults) {
        return total > maxResults;
    }

    private static boolean isFinite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
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

    private record Hit(SensorBlock block, SensorEntity entity, double distance) {
        static Hit block(SensorBlock block, double distance) {
            return new Hit(block, null, distance);
        }

        static Hit entity(SensorEntity entity, double distance) {
            return new Hit(null, entity, distance);
        }
    }
}
