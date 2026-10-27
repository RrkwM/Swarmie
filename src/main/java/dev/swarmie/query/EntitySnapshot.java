package dev.swarmie.query;

import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Immutable entity summary; no live Entity reference retained. */
public record EntitySnapshot(
        Identifier typeId,
        UUID uuid,
        Vec3 position,
        AABB boundingBox,
        double distanceToOrigin) {

    public EntitySnapshot {
        position = new Vec3(position.x, position.y, position.z);
        boundingBox = new AABB(
                boundingBox.minX, boundingBox.minY, boundingBox.minZ,
                boundingBox.maxX, boundingBox.maxY, boundingBox.maxZ);
    }
}
