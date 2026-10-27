package dev.swarmie.sensor;

/** Bounded local-sensor parameters. All values are finite and capped. */
public record SensorConfig(double range, double fovDegrees, int maxResults, boolean lineOfSight) {
    public static final double MAX_RANGE = 16.0D;
    public static final double MAX_FOV_DEGREES = 180.0D;
    public static final int MAX_RESULTS = 64;

    public SensorConfig {
        if (!Double.isFinite(range) || range <= 0.0D || range > MAX_RANGE) {
            throw new IllegalArgumentException("sensor range must be in (0, " + MAX_RANGE + "]");
        }
        if (!Double.isFinite(fovDegrees) || fovDegrees <= 0.0D || fovDegrees > MAX_FOV_DEGREES) {
            throw new IllegalArgumentException("sensor FOV must be in (0, " + MAX_FOV_DEGREES + "]");
        }
        if (maxResults < 1 || maxResults > MAX_RESULTS) {
            throw new IllegalArgumentException("sensor maxResults must be in [1, " + MAX_RESULTS + "]");
        }
    }

    public static SensorConfig defaults() {
        return new SensorConfig(8.0D, 90.0D, 32, false);
    }

    public SensorConfig withRange(double newRange) {
        return new SensorConfig(newRange, this.fovDegrees, this.maxResults, this.lineOfSight);
    }

    public SensorConfig withLineOfSight(boolean enabled) {
        return new SensorConfig(this.range, this.fovDegrees, this.maxResults, enabled);
    }
}
