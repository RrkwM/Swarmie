package dev.swarmie.dcpu;

/** Language-neutral motion submission/status seam for the MMIO device. */
public interface MotionPort {
    enum State {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        INTERRUPTED,
        UNKNOWN
    }

    record Submission(boolean accepted, long commandId, int errorCode) {
        public static Submission accepted(long commandId) {
            return new Submission(true, commandId, 0);
        }

        public static Submission rejected(int errorCode) {
            return new Submission(false, -1L, errorCode);
        }
    }

    record Status(State state, int errorCode) {
    }

    Submission submitTimedFlight(int vxMilli, int vyMilli, int vzMilli, int durationTicks);

    Status status(long commandId);

    void stop();
}
