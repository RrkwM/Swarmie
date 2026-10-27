package dev.swarmie;

import net.minecraft.world.phys.Vec3;

/**
 * Minimal velocity-based flight controller for the Swarmie body.
 *
 * <p>Units are Minecraft blocks per tick. The controller only shapes the
 * desired velocity; actual displacement and collision response are performed by
 * {@code Entity.move(MoverType.SELF, ...)} on the logical server.
 *
 * <p>No AI, pathfinding or player-input simulation is involved.
 */
public final class SwarmieFlightController {
    public static final double MAX_SPEED = 0.35D;
    public static final double MAX_ACCELERATION = 0.04D;
    public static final double IDLE_DAMPING = 0.90D;

    private static final double ZERO_EPSILON_SQR = 1.0E-8D;

    private Vec3 desiredVelocity = Vec3.ZERO;

    /** Sets the target velocity, clamped to {@link #MAX_SPEED}. */
    public void setDesiredVelocity(Vec3 desired) {
        if (desired == null || !isFinite(desired)) {
            throw new IllegalArgumentException("desired velocity must be finite");
        }
        this.desiredVelocity = clampLength(desired, MAX_SPEED);
    }

    /** Requests a controlled stop; deceleration happens in {@link #update(Vec3)}. */
    public void stop() {
        this.desiredVelocity = Vec3.ZERO;
    }

    public Vec3 getDesiredVelocity() {
        return this.desiredVelocity;
    }

    /**
     * Advances one tick.
     *
     * @param currentVelocity the entity's current native velocity
     * @return the velocity to apply before calling {@code Entity.move}
     */
    public Vec3 update(Vec3 currentVelocity) {
        Vec3 current = isFinite(currentVelocity) ? currentVelocity : Vec3.ZERO;

        Vec3 delta;
        if (this.desiredVelocity.lengthSqr() < ZERO_EPSILON_SQR) {
            // Damping: decay toward zero, but the per-tick change is still
            // limited by MAX_ACCELERATION so stopping is gradual.
            delta = current.scale(IDLE_DAMPING).subtract(current);
        }
        else {
            delta = this.desiredVelocity.subtract(current);
        }

        delta = clampLength(delta, MAX_ACCELERATION);
        Vec3 next = current.add(delta);
        return isFinite(next) ? next : Vec3.ZERO;
    }

    private static Vec3 clampLength(Vec3 vector, double maxLength) {
        double length = vector.length();
        if (length <= maxLength || length < 1.0E-9D) {
            return vector;
        }
        return vector.scale(maxLength / length);
    }

    private static boolean isFinite(Vec3 vector) {
        return vector != null
                && Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }
}
