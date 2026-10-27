package dev.swarmie.sensor;

import dev.swarmie.query.QueryStatus;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Immutable point-in-time sensor observation. */
public record SensorObservation(
        QueryStatus status,
        Vec3 origin,
        Vec3 direction,
        SensorConfig config,
        List<SensorBlock> blocks,
        List<SensorEntity> entities,
        boolean truncated,
        boolean incomplete,
        String reason) {

    public SensorObservation {
        origin = new Vec3(origin.x, origin.y, origin.z);
        direction = new Vec3(direction.x, direction.y, direction.z);
        blocks = List.copyOf(blocks);
        entities = List.copyOf(entities);
    }

    public boolean successful() {
        return status == QueryStatus.OK;
    }

    public static SensorObservation error(
            QueryStatus status, Vec3 origin, Vec3 direction, SensorConfig config, String reason) {
        Vec3 safeOrigin = origin == null ? Vec3.ZERO : origin;
        Vec3 safeDirection = direction == null ? Vec3.ZERO : direction;
        SensorConfig safeConfig = config == null ? SensorConfig.defaults() : config;
        return new SensorObservation(
                status, safeOrigin, safeDirection, safeConfig, List.of(), List.of(), false, true, reason);
    }
}
