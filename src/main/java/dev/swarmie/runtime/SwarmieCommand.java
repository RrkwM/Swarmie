package dev.swarmie.runtime;

/**
 * One command's definition, execution progress and final result.
 *
 * <p>Runtime owns transitions; subclasses only implement their start/tick
 * behaviour. Terminal states are final.
 */
public abstract class SwarmieCommand {
    public enum State {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        INTERRUPTED;

        public boolean isTerminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == INTERRUPTED;
        }
    }

    public enum Type {
        TIMED_FLIGHT,
        STOP,
        MINE_BLOCK,
        USE_ITEM_ON_BLOCK
    }

    private final long id;
    private final Type type;
    private final long submittedTick;
    private State state = State.QUEUED;
    private long startedTick = -1L;
    private long completedTick = -1L;
    private String result = "";
    private String correlationId;
    private boolean terminalNotified;

    protected SwarmieCommand(long id, Type type, long submittedTick) {
        this.id = id;
        this.type = type;
        this.submittedTick = submittedTick;
    }

    public final long getId() {
        return this.id;
    }

    public final Type getType() {
        return this.type;
    }

    public final State getState() {
        return this.state;
    }

    public final long getSubmittedTick() {
        return this.submittedTick;
    }

    public final long getStartedTick() {
        return this.startedTick;
    }

    public final long getCompletedTick() {
        return this.completedTick;
    }

    public final String getResult() {
        return this.result;
    }

    public final String getCorrelationId() {
        return this.correlationId;
    }

    final void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    final boolean markTerminalNotified() {
        if (this.terminalNotified) {
            return false;
        }
        this.terminalNotified = true;
        return true;
    }

    final void markRunning(long tick) {
        if (this.state != State.QUEUED) {
            throw new IllegalStateException("cannot start command " + this.id + " from " + this.state);
        }
        this.state = State.RUNNING;
        this.startedTick = tick;
    }

    final void succeed(long tick, String result) {
        transition(State.RUNNING, State.SUCCEEDED);
        this.completedTick = tick;
        this.result = result == null ? "" : result;
    }

    final void fail(long tick, String result) {
        transition(State.RUNNING, State.FAILED);
        this.completedTick = tick;
        this.result = result == null ? "" : result;
    }

    final void cancel(long tick, String result) {
        if (this.state == State.QUEUED) {
            this.state = State.CANCELLED;
        }
        else {
            transition(State.RUNNING, State.CANCELLED);
        }
        this.completedTick = tick;
        this.result = result == null ? "" : result;
    }

    final void interrupt(long tick, String result) {
        if (this.state == State.QUEUED) {
            this.state = State.INTERRUPTED;
        }
        else {
            transition(State.RUNNING, State.INTERRUPTED);
        }
        this.completedTick = tick;
        this.result = result == null ? "" : result;
    }

    private void transition(State from, State to) {
        if (this.state.isTerminal()) {
            throw new IllegalStateException("command " + this.id + " is already terminal: " + this.state);
        }
        if (this.state != from) {
            throw new IllegalStateException("illegal transition " + this.state + " -> " + to);
        }
        this.state = to;
    }

    final void start(FlightTarget target) {
        onStart(target);
    }

    /**
     * @return a non-null result when the command has just completed, otherwise null
     */
    final String tick(FlightTarget target) {
        return onTick(target);
    }

    final void cancelInternal(FlightTarget target) {
        onCancel(target);
    }

    protected abstract void onStart(FlightTarget target);

    protected abstract String onTick(FlightTarget target);

    protected void onCancel(FlightTarget target) {
        target.stopFlight();
    }
}
