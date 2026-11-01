package dev.swarmie.dcpu;

import java.util.function.IntConsumer;

/**
 * MMIO motion device. Register layout:
 *
 * <pre>
 * 0x9000 CMD      1 = timed flight, 2 = stop, 3 = acknowledge terminal
 * 0x9001 STAT     0 READY, 1 BUSY, 2 SUCCEEDED, 3 FAILED, 4 FAULTED
 * 0x9002 ERR      0 none, 1 invalid command, 2 invalid argument, 3 busy,
 *                 4 terminal not acknowledged, 5 runtime rejected,
 *                 6 runtime fault
 * 0x9003 duration ticks (1..6000)
 * 0x9004 vx milli-blocks/tick (signed)
 * 0x9005 vy milli-blocks/tick (signed)
 * 0x9006 vz milli-blocks/tick (signed)
 * </pre>
 *
 * The terminal status is latched; the guest must write CMD=3 before another
 * command. One interrupt is queued per terminal/fault transition.
 */
public final class MotionDevice implements MmioDevice {
    public static final int START = 0x9000;
    public static final int END = 0x9006;
    public static final int CMD = 0x9000;
    public static final int STAT = 0x9001;
    public static final int ERR = 0x9002;
    public static final int DURATION = 0x9003;
    public static final int VX = 0x9004;
    public static final int VY = 0x9005;
    public static final int VZ = 0x9006;

    public static final int CMD_TIMED_FLIGHT = 1;
    public static final int CMD_STOP = 2;
    public static final int CMD_ACK = 3;

    public static final int READY = 0;
    public static final int BUSY = 1;
    public static final int SUCCEEDED = 2;
    public static final int FAILED = 3;
    public static final int FAULTED = 4;

    public static final int ERR_NONE = 0;
    public static final int ERR_INVALID_COMMAND = 1;
    public static final int ERR_INVALID_ARGUMENT = 2;
    public static final int ERR_BUSY = 3;
    public static final int ERR_NOT_ACKNOWLEDGED = 4;
    public static final int ERR_RUNTIME_REJECTED = 5;
    public static final int ERR_RUNTIME_FAULT = 6;

    private final MotionPort port;
    private final IntConsumer interruptSink;
    private int status = READY;
    private int error = ERR_NONE;
    private int duration = 20;
    private int vx = 0;
    private int vy = 0;
    private int vz = 0;
    private long commandId = -1L;
    private boolean interruptQueued;

    public MotionDevice(MotionPort port, IntConsumer interruptSink) {
        if (port == null) {
            throw new NullPointerException("port");
        }
        this.port = port;
        this.interruptSink = interruptSink == null ? message -> {
        } : interruptSink;
    }

    @Override
    public int startAddress() {
        return START;
    }

    @Override
    public int endAddress() {
        return END;
    }

    @Override
    public int read(int address) {
        return switch (address) {
            case STAT -> this.status;
            case ERR -> this.error;
            case DURATION -> this.duration;
            case VX -> this.vx & 0xffff;
            case VY -> this.vy & 0xffff;
            case VZ -> this.vz & 0xffff;
            default -> 0;
        };
    }

    @Override
    public void write(int address, int value) {
        switch (address) {
            case CMD -> this.command(value & 0xffff);
            case DURATION -> this.duration = value & 0xffff;
            case VX -> this.vx = sign16(value);
            case VY -> this.vy = sign16(value);
            case VZ -> this.vz = sign16(value);
            default -> {
                // STAT/ERR are read-only.
            }
        }
    }

    @Override
    public void tick() {
        if (this.status != BUSY || this.commandId < 0) {
            return;
        }
        MotionPort.Status next = this.port.status(this.commandId);
        switch (next.state()) {
            case SUCCEEDED -> this.terminal(SUCCEEDED, next.errorCode());
            case FAILED -> this.terminal(FAILED, next.errorCode() == 0
                    ? ERR_RUNTIME_FAULT : next.errorCode());
            case CANCELLED, INTERRUPTED -> this.terminal(FAULTED, ERR_RUNTIME_FAULT);
            case UNKNOWN -> this.terminal(FAULTED, ERR_RUNTIME_FAULT);
            case QUEUED, RUNNING -> {
            }
        }
    }

    public int status() {
        return this.status;
    }

    public int error() {
        return this.error;
    }

    public long commandId() {
        return this.commandId;
    }

    public int duration() {
        return this.duration;
    }

    public int vx() {
        return this.vx;
    }

    public int vy() {
        return this.vy;
    }

    public int vz() {
        return this.vz;
    }

    private void command(int value) {
        if (value == CMD_ACK) {
            if (this.status == SUCCEEDED || this.status == FAILED || this.status == FAULTED) {
                this.status = READY;
                this.error = ERR_NONE;
                this.commandId = -1L;
                this.interruptQueued = false;
            }
            else {
                this.error = ERR_NOT_ACKNOWLEDGED;
            }
            return;
        }
        if (this.status == BUSY) {
            this.error = ERR_BUSY;
            return;
        }
        if (this.status == SUCCEEDED || this.status == FAILED || this.status == FAULTED) {
            this.error = ERR_NOT_ACKNOWLEDGED;
            return;
        }
        if (value == CMD_STOP) {
            this.port.stop();
            this.status = READY;
            this.error = ERR_NONE;
            return;
        }
        if (value != CMD_TIMED_FLIGHT) {
            this.fault(ERR_INVALID_COMMAND);
            return;
        }
        if (this.duration < 1 || this.duration > 6000
                || Math.abs(this.vx) > 350
                || Math.abs(this.vy) > 350
                || Math.abs(this.vz) > 350) {
            this.fault(ERR_INVALID_ARGUMENT);
            return;
        }
        MotionPort.Submission submission = this.port.submitTimedFlight(
                this.vx, this.vy, this.vz, this.duration);
        if (!submission.accepted()) {
            this.fault(submission.errorCode() == 0 ? ERR_RUNTIME_REJECTED : submission.errorCode());
            return;
        }
        this.commandId = submission.commandId();
        this.status = BUSY;
        this.error = ERR_NONE;
        this.interruptQueued = false;
    }

    private void terminal(int nextStatus, int nextError) {
        if (this.status != BUSY) {
            return;
        }
        this.status = nextStatus;
        this.error = nextError;
        if (!this.interruptQueued) {
            this.interruptQueued = true;
            this.interruptSink.accept(1);
        }
    }

    private void fault(int nextError) {
        this.status = FAULTED;
        this.error = nextError;
        if (!this.interruptQueued) {
            this.interruptQueued = true;
            this.interruptSink.accept(1);
        }
    }

    private static int sign16(int value) {
        int word = value & 0xffff;
        return word >= 0x8000 ? word - 0x10000 : word;
    }
}
