package dev.swarmie.runtime;

import net.minecraft.world.phys.Vec3;

/**
 * Fly with a target velocity for a fixed number of ticks, then decelerate to a
 * stop and only succeed once the speed is below the stopping threshold.
 */
final class TimedFlightCommand extends SwarmieCommand {
    private final Vec3 velocity;
    private final int durationTicks;
    private int remainingTicks;
    private boolean stopping;

    TimedFlightCommand(long id, long submittedTick, Vec3 velocity, int durationTicks) {
        super(id, Type.TIMED_FLIGHT, submittedTick);
        this.velocity = velocity;
        this.durationTicks = durationTicks;
        this.remainingTicks = durationTicks;
    }

    @Override
    protected void onStart(FlightTarget target) {
        this.remainingTicks = this.durationTicks;
        this.stopping = false;
        target.setDesiredVelocity(this.velocity);
    }

    @Override
    protected String onTick(FlightTarget target) {
        if (!this.stopping) {
            this.remainingTicks--;
            if (this.remainingTicks <= 0) {
                target.stopFlight();
                this.stopping = true;
            }
        }

        if (this.stopping && target.getVelocity().length() <= SwarmieRuntime.STOP_SPEED_THRESHOLD) {
            return "timed flight complete (duration " + this.durationTicks + " ticks)";
        }
        return null;
    }

    @Override
    protected void onCancel(FlightTarget target) {
        target.stopFlight();
    }
}
