package dev.swarmie.dcpu.bridge;

import dev.swarmie.contract.CommandSpec;
import dev.swarmie.contract.Vec3Dto;
import dev.swarmie.dcpu.MotionPort;
import dev.swarmie.runtime.SubmissionResult;
import dev.swarmie.runtime.SwarmieCommand;
import dev.swarmie.runtime.SwarmieRuntime;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Reuses the existing server-side SwarmieRuntime; it does not call Minecraft
 * world APIs or duplicate movement behavior.
 */
final class RuntimeMotionPort implements MotionPort {
    private static final int MAX_MILLI = 350;
    private static final int MAX_DURATION = 6000;

    private final SwarmieRuntime runtime;
    private long nextCorrelation;

    RuntimeMotionPort(SwarmieRuntime runtime) {
        if (runtime == null) {
            throw new NullPointerException("runtime");
        }
        this.runtime = runtime;
    }

    @Override
    public Submission submitTimedFlight(int vxMilli, int vyMilli, int vzMilli, int durationTicks) {
        if (Math.abs(vxMilli) > MAX_MILLI
                || Math.abs(vyMilli) > MAX_MILLI
                || Math.abs(vzMilli) > MAX_MILLI
                || durationTicks < 1 || durationTicks > MAX_DURATION) {
            return Submission.rejected(MotionDeviceError.INVALID_ARGUMENT);
        }
        if (this.runtime.isTerminated()) {
            return Submission.rejected(MotionDeviceError.RUNTIME_REJECTED);
        }
        // MMIO supplies a numeric projection; the shared Contract still owns
        // the language-neutral command shape and bounds.
        CommandSpec.TimedFlight command = new CommandSpec.TimedFlight(
                new Vec3Dto(vxMilli / 1000.0, vyMilli / 1000.0, vzMilli / 1000.0),
                durationTicks);
        SubmissionResult result = this.runtime.submitTimedFlight(
                new Vec3(command.velocity().x(), command.velocity().y(), command.velocity().z()),
                command.durationTicks(),
                "dcpu-" + this.nextCorrelation++);
        if (!result.accepted()) {
            return Submission.rejected(MotionDeviceError.RUNTIME_REJECTED);
        }
        return Submission.accepted(result.commandId());
    }

    @Override
    public Status status(long commandId) {
        Optional<SwarmieCommand> found = this.runtime.getCommand(commandId);
        if (found.isEmpty()) {
            return new Status(State.UNKNOWN, MotionDeviceError.RUNTIME_FAULT);
        }
        return switch (found.get().getState()) {
            case QUEUED -> new Status(State.QUEUED, 0);
            case RUNNING -> new Status(State.RUNNING, 0);
            case SUCCEEDED -> new Status(State.SUCCEEDED, 0);
            case FAILED, CANCELLED -> new Status(State.FAILED, MotionDeviceError.RUNTIME_FAULT);
            case INTERRUPTED -> new Status(State.INTERRUPTED, MotionDeviceError.RUNTIME_FAULT);
        };
    }

    @Override
    public void stop() {
        this.runtime.submitStop("dcpu-stop");
    }

    private static final class MotionDeviceError {
        private static final int INVALID_ARGUMENT = 2;
        private static final int RUNTIME_REJECTED = 5;
        private static final int RUNTIME_FAULT = 6;
    }
}
