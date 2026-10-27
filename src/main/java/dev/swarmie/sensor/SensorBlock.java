package dev.swarmie.sensor;

import dev.swarmie.query.BlockSnapshot;
import net.minecraft.world.phys.Vec3;

/** Detected block with sensor-relative geometry. */
public record SensorBlock(BlockSnapshot block, Vec3 relative, double distance) {
    public SensorBlock {
        relative = new Vec3(relative.x, relative.y, relative.z);
    }
}
