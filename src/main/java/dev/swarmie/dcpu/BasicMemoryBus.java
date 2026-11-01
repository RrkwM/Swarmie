package dev.swarmie.dcpu;

import java.util.ArrayList;
import java.util.List;

/** 64K-word RAM with a small fixed set of MMIO windows. */
public final class BasicMemoryBus implements MemoryBus {
    private final int[] ram = new int[Dcpu16.MEMORY_WORDS];
    private final List<MmioDevice> devices = new ArrayList<>();

    public void map(MmioDevice device) {
        if (device == null || device.startAddress() > device.endAddress()) {
            throw new IllegalArgumentException("invalid MMIO device");
        }
        this.devices.add(device);
    }

    @Override
    public int read(int address) {
        int word = address & 0xffff;
        for (MmioDevice device : this.devices) {
            if (word >= device.startAddress() && word <= device.endAddress()) {
                return device.read(word) & 0xffff;
            }
        }
        return this.ram[word];
    }

    @Override
    public void write(int address, int value) {
        int word = address & 0xffff;
        for (MmioDevice device : this.devices) {
            if (word >= device.startAddress() && word <= device.endAddress()) {
                device.write(word, value & 0xffff);
                return;
            }
        }
        this.ram[word] = value & 0xffff;
    }

    public int ram(int address) {
        return this.ram[address & 0xffff];
    }

    public void setRam(int address, int value) {
        this.ram[address & 0xffff] = value & 0xffff;
    }

    public List<MmioDevice> devices() {
        return List.copyOf(this.devices);
    }
}
