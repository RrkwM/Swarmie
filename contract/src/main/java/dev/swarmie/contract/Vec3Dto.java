package dev.swarmie.contract;

/** Floating-point vector for velocity and hit positions; units are documented per command. */
public record Vec3Dto(double x, double y, double z) {
    public static final Vec3Dto ZERO = new Vec3Dto(0, 0, 0);

    public Vec3Dto {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("vector components must be finite");
        }
    }
}
