package dev.swarmie.sensor;

import dev.swarmie.query.EntitySnapshot;
import net.minecraft.world.phys.Vec3;

/** Detected entity with sensor-relative geometry. */
public record SensorEntity(EntitySnapshot entity, Vec3 relative, double distance, double angleDegrees) {
    public SensorEntity {
        relative = new Vec3(relative.x, relative.y, relative.z);
    }
}
