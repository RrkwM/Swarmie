package dev.swarmie.runtime;

import net.minecraft.world.phys.Vec3;

/**
 * The narrow physical interface the Runtime is allowed to use. Implemented by
 * {@code SwarmieEntity}; tests can supply a lightweight implementation without
 * reimplementing the Runtime.
 */
public interface FlightTarget {
    void setDesiredVelocity(Vec3 velocity);

    void stopFlight();

    Vec3 getVelocity();

    boolean isRemoved();
}
