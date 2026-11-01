package dev.swarmie.dcpu;

/** A fixed MMIO window. Devices never see Minecraft objects. */
public interface MmioDevice {
    int startAddress();

    int endAddress();

    int read(int address);

    void write(int address, int value);

    default void tick() {
    }
}
