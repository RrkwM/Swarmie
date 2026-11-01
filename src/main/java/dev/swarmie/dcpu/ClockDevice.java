package dev.swarmie.dcpu;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Read-only clock/status device:
 *
 * <pre>
 * 0x9100 server tick low 16 bits
 * 0x9101 server tick high 16 bits
 * 0x9102 VM status: 0 running, 1 halted, 2 faulted
 * 0x9103 pending interrupt count
 * 0x9104 capability/version word (1)
 * </pre>
 */
public final class ClockDevice implements MmioDevice {
    public static final int START = 0x9100;
    public static final int END = 0x9104;
    public static final int VERSION = 1;

    private final LongSupplier serverTick;
    private final IntSupplier vmStatus;
    private final IntSupplier pendingInterrupts;

    public ClockDevice(LongSupplier serverTick, IntSupplier vmStatus, IntSupplier pendingInterrupts) {
        if (serverTick == null || vmStatus == null || pendingInterrupts == null) {
            throw new NullPointerException("clock suppliers");
        }
        this.serverTick = serverTick;
        this.vmStatus = vmStatus;
        this.pendingInterrupts = pendingInterrupts;
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
        long tick = this.serverTick.getAsLong();
        return switch (address) {
            case 0x9100 -> (int) (tick & 0xffffL);
            case 0x9101 -> (int) ((tick >>> 16) & 0xffffL);
            case 0x9102 -> this.vmStatus.getAsInt();
            case 0x9103 -> this.pendingInterrupts.getAsInt();
            case 0x9104 -> VERSION;
            default -> 0;
        };
    }

    @Override
    public void write(int address, int value) {
        // Read-only.
    }
}
