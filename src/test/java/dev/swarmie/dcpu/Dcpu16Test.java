package dev.swarmie.dcpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Dcpu16Test {
    private static int basic(int opcode, int a, int b) {
        return ((a & 0x3f) << 10) | ((b & 0x1f) << 5) | (opcode & 0x1f);
    }

    private static int special(int opcode, int a) {
        return ((a & 0x3f) << 10) | ((opcode & 0x1f) << 5);
    }

    @Test
    void setImmediateAndMemoryUseTheDocumentedOperandModes() {
        BasicMemoryBus bus = new BasicMemoryBus();
        Dcpu16 cpu = new Dcpu16(bus);
        cpu.loadProgram(0, new int[]{
                basic(0x01, 0x1f, Dcpu16.REG_A), 0x1234,
                basic(0x01, Dcpu16.REG_A, 0x1e), 0x1000
        });

        Dcpu16.Result first = cpu.run(1);
        assertEquals(1, first.cycles());
        assertEquals(0x1234, cpu.register(Dcpu16.REG_A));
        assertEquals(2, cpu.pc());

        Dcpu16.Result second = cpu.run(1);
        assertEquals(1, second.cycles());
        assertEquals(0x1234, bus.ram(0x1000));
        assertEquals(4, cpu.pc());
    }

    @Test
    void cycleBudgetStopsAtTheBoundaryAndResumes() {
        BasicMemoryBus bus = new BasicMemoryBus();
        Dcpu16 cpu = new Dcpu16(bus);
        cpu.loadProgram(0, new int[]{
                basic(0x01, 0x1f, Dcpu16.REG_A), 1,
                basic(0x01, 0x1f, Dcpu16.REG_A), 2
        });

        assertEquals(1, cpu.run(1).cycles());
        assertEquals(1, cpu.register(Dcpu16.REG_A));
        assertEquals(1, cpu.run(1).cycles());
        assertEquals(2, cpu.register(Dcpu16.REG_A));
        assertEquals(0, cpu.run(0).cycles());
    }

    @Test
    void unknownOpcodeFaultsExplicitly() {
        BasicMemoryBus bus = new BasicMemoryBus();
        bus.setRam(0, basic(0x02, 0, Dcpu16.REG_A)); // ADD is outside subset
        Dcpu16 cpu = new Dcpu16(bus);

        Dcpu16.Result result = cpu.run(1);

        assertEquals(Dcpu16.State.FAULTED, result.state());
        assertEquals(0xfffe, cpu.faultCode());
        assertEquals(1, result.cycles());
    }

    @Test
    void unsupportedSpecialOpcodeFaultsExplicitly() {
        BasicMemoryBus bus = new BasicMemoryBus();
        bus.setRam(0, special(0x0c, 0)); // IAQ is outside subset
        Dcpu16 cpu = new Dcpu16(bus);

        Dcpu16.Result result = cpu.run(1);

        assertEquals(Dcpu16.State.FAULTED, result.state());
        assertEquals(0xfffd, cpu.faultCode());
    }

    @Test
    void interruptQueueHasABoundedOverflowFault() {
        BasicMemoryBus bus = new BasicMemoryBus();
        Dcpu16 cpu = new Dcpu16(bus);
        for (int i = 0; i < 256; i++) {
            cpu.queueInterrupt(i);
        }
        assertEquals(256, cpu.pendingInterrupts());

        cpu.queueInterrupt(256);

        assertEquals(Dcpu16.State.FAULTED, cpu.state());
        assertEquals(0xffff, cpu.faultCode());
    }

    @Test
    void iasAndRfiDeliverOneInterruptAndRestorePc() {
        BasicMemoryBus bus = new BasicMemoryBus();
        Dcpu16 cpu = new Dcpu16(bus);
        cpu.loadProgram(0, new int[]{
                basic(0x01, 0x1f, Dcpu16.REG_A), 0x0100, // SET A, handler
                special(0x0a, Dcpu16.REG_A),             // IAS A
                basic(0x01, 0x1f, Dcpu16.REG_A), 0x002a, // poll
                special(0x0b, 0),                        // RFI
                basic(0x01, 0x1f, Dcpu16.REG_B), 0x0000  // handler at 0x100
        });
        cpu.writeRam(0x0100, special(0x0b, 0));
        cpu.writeRam(0x0101, 0x0000);

        cpu.run(2); // SET A, IAS
        assertEquals(0x0100, cpu.ia());
        int pollPc = cpu.pc();
        cpu.queueInterrupt(0x0042);
        Dcpu16.Result dispatch = cpu.run(1);
        assertEquals(1, dispatch.cycles());
        assertEquals(0x0100, cpu.pc());
        assertEquals(0x0042, cpu.register(Dcpu16.REG_A));
        assertEquals(0, cpu.pendingInterrupts());

        cpu.run(1); // RFI at handler
        assertEquals(pollPc, cpu.pc());
        assertEquals(0x0100, cpu.register(Dcpu16.REG_A));
    }

    @Test
    void stackAndLiteralOperandsAreBounded() {
        BasicMemoryBus bus = new BasicMemoryBus();
        Dcpu16 cpu = new Dcpu16(bus);
        cpu.loadProgram(0, new int[]{
                basic(0x01, 0x20, Dcpu16.REG_A), // literal destination; no-op
                basic(0x01, 0x20, Dcpu16.REG_B)  // literal source -1
        });
        cpu.run(2);
        assertEquals(0xffff, cpu.register(Dcpu16.REG_B));
        assertEquals(0xffff, cpu.sp());
    }
}
