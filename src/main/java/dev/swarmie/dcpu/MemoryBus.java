package dev.swarmie.dcpu;

/** Word-addressed bus used by the DCPU core. */
public interface MemoryBus {
    int read(int address);

    void write(int address, int value);
}
