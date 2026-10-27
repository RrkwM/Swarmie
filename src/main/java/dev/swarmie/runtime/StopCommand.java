package dev.swarmie.runtime;

/**
 * Request a controlled stop and succeed once the native velocity is below the
 * stopping threshold.
 */
final class StopCommand extends SwarmieCommand {
    StopCommand(long id, long submittedTick) {
        super(id, Type.STOP, submittedTick);
    }

    @Override
    protected void onStart(FlightTarget target) {
        target.stopFlight();
    }

    @Override
    protected String onTick(FlightTarget target) {
        if (target.getVelocity().length() <= SwarmieRuntime.STOP_SPEED_THRESHOLD) {
            return "stopped";
        }
        return null;
    }

    @Override
    protected void onCancel(FlightTarget target) {
        target.stopFlight();
    }
}
