package dev.swarmie.dcpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClockDeviceTest {
    @Test
    void readOnlyClockSplitsTickAndReportsVmState() {
        BasicMemoryBus bus = new BasicMemoryBus();
        ClockDevice clock = new ClockDevice(() -> 0x12345678L, () -> 2, () -> 3);
        bus.map(clock);

        assertEquals(0x5678, bus.read(0x9100));
        assertEquals(0x1234, bus.read(0x9101));
        assertEquals(2, bus.read(0x9102));
        assertEquals(3, bus.read(0x9103));
        assertEquals(ClockDevice.VERSION, bus.read(0x9104));
        bus.write(0x9100, 0xffff);
        assertEquals(0x5678, bus.read(0x9100));
    }
}
