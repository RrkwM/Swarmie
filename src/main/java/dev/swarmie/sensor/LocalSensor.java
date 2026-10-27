package dev.swarmie.sensor;

import dev.swarmie.SwarmieEntity;

/**
 * Local-sensing capability. It observes from the robot's authoritative pose and
 * cannot be used to query arbitrary world positions.
 */
public interface LocalSensor {
    SensorObservation observe(SwarmieEntity robot, SensorConfig config);
}
