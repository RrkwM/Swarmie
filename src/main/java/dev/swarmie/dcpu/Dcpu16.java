package dev.swarmie.dcpu;

/**
 * A deliberately limited DCPU-16 1.7 subset for the MMIO bridge spike.
 *
 * <p>Supported basic instruction: SET (0x01). Supported special instructions:
 * IAS (0x0a) and RFI (0x0b). All other opcodes fault. One fetched instruction
 * costs one cycle; an interrupt dispatch also costs one cycle. This is an
 * instruction-budget subset, not the complete DCPU timing model.
 */
public final class Dcpu16 {
    public static final int MEMORY_WORDS = 0x10000;
    public static final int REG_A = 0;
    public static final int REG_B = 1;
    public static final int REG_C = 2;
    public static final int REG_X = 3;
    public static final int REG_Y = 4;
    public static final int REG_Z = 5;
    public static final int REG_I = 6;
    public static final int REG_J = 7;

    public enum State {
        RUNNING,
        HALTED,
        FAULTED
    }

    public record Result(int cycles, State state, int pc, int faultCode) {
    }

    private final MemoryBus bus;
    private final int[] registers = new int[8];
    private final java.util.ArrayDeque<Integer> interrupts = new java.util.ArrayDeque<>();
    private int pc;
    private int sp = 0xffff;
    private int ex;
    private int ia;
    private boolean interruptQueueing;
    private State state = State.RUNNING;
    private int faultCode;
    private int lastCycles;
    private int interruptMessage = -1;

    public Dcpu16(MemoryBus bus) {
        if (bus == null) {
            throw new NullPointerException("bus");
        }
        this.bus = bus;
    }

    public State state() {
        return this.state;
    }

    public int faultCode() {
        return this.faultCode;
    }

    public int pc() {
        return this.pc;
    }

    public int sp() {
        return this.sp;
    }

    public int ia() {
        return this.ia;
    }

    public int ex() {
        return this.ex;
    }

    public int register(int index) {
        if (index < 0 || index >= this.registers.length) {
            throw new IndexOutOfBoundsException("register " + index);
        }
        return this.registers[index];
    }

    public int readRam(int address) {
        return this.bus.read(address) & 0xffff;
    }

    public void writeRam(int address, int value) {
        this.bus.write(address, value & 0xffff);
    }

    public void loadProgram(int address, int[] words) {
        if (words == null) {
            throw new NullPointerException("words");
        }
        for (int i = 0; i < words.length; i++) {
            this.writeRam(address + i, words[i]);
        }
    }

    public int pendingInterrupts() {
        return this.interrupts.size();
    }

    public int interruptMessage() {
        return this.interruptMessage;
    }

    public void queueInterrupt(int message) {
        if (this.state != State.RUNNING) {
            return;
        }
        if (this.interrupts.size() >= 256) {
            this.fault(0xffff);
            return;
        }
        this.interrupts.addLast(message & 0xffff);
    }

    /**
     * Executes at most {@code cycleBudget} cycles. It never executes past the
     * budget; a fault or halt stops earlier.
     */
    public Result run(int cycleBudget) {
        if (cycleBudget < 0) {
            throw new IllegalArgumentException("cycleBudget must be non-negative");
        }
        int used = 0;
        while (used < cycleBudget && this.state == State.RUNNING) {
            if (this.deliverInterrupt()) {
                used++;
                continue;
            }
            int instruction = this.bus.read(this.pc) & 0xffff;
            this.pc = (this.pc + 1) & 0xffff;
            int opcode = instruction & 0x1f;
            int b = (instruction >>> 5) & 0x1f;
            int a = (instruction >>> 10) & 0x3f;
            try {
                this.execute(opcode, a, b);
            }
            catch (Fault fault) {
                this.fault(fault.code);
            }
            used++;
        }
        this.lastCycles = used;
        return new Result(used, this.state, this.pc, this.faultCode);
    }

    public int lastCycles() {
        return this.lastCycles;
    }

    private boolean deliverInterrupt() {
        if (this.interrupts.isEmpty() || this.ia == 0 || this.interruptQueueing) {
            return false;
        }
        int message = this.interrupts.removeFirst();
        this.push(this.pc);
        this.push(this.registers[REG_A]);
        this.interruptQueueing = true;
        this.interruptMessage = message;
        this.pc = this.ia;
        this.registers[REG_A] = message;
        return true;
    }

    private void execute(int opcode, int a, int b) {
        if (opcode == 0x00) {
            this.special(b, a);
            return;
        }
        if (opcode == 0x01) {
            int value = this.get(a);
            this.set(b, value);
            return;
        }
        throw new Fault(0xfffe);
    }

    private void special(int special, int a) {
        switch (special) {
            case 0x0a -> this.ia = this.get(a) & 0xffff;
            case 0x0b -> {
                this.registers[REG_A] = this.pop();
                this.pc = this.pop();
                this.interruptQueueing = false;
                this.interruptMessage = -1;
            }
            default -> throw new Fault(0xfffd);
        }
    }

    private int get(int code) {
        if (code <= 0x07) {
            return this.registers[code];
        }
        if (code <= 0x0f) {
            return this.bus.read(this.registers[code - 0x08]) & 0xffff;
        }
        if (code <= 0x17) {
            int address = (this.next() + this.registers[code - 0x10]) & 0xffff;
            return this.bus.read(address) & 0xffff;
        }
        if (code >= 0x20) {
            return code - 0x21 & 0xffff;
        }
        return switch (code) {
            case 0x1b -> this.sp;
            case 0x1c -> this.pc;
            case 0x1d -> this.ex;
            case 0x1e -> this.bus.read(this.next()) & 0xffff;
            case 0x1f -> this.next();
            default -> throw new Fault(0xfffc);
        };
    }

    private void set(int code, int value) {
        value &= 0xffff;
        if (code >= 0x20) {
            // DCPU literal destinations fail silently.
            return;
        }
        if (code <= 0x07) {
            this.registers[code] = value;
        }
        else if (code <= 0x0f) {
            this.bus.write(this.registers[code - 0x08], value);
        }
        else if (code <= 0x17) {
            int address = (this.next() + this.registers[code - 0x10]) & 0xffff;
            this.bus.write(address, value);
        }
        else {
            switch (code) {
                case 0x1b -> this.sp = value;
                case 0x1c -> this.pc = value;
                case 0x1d -> this.ex = value;
                case 0x1e -> this.bus.write(this.next(), value);
                case 0x1f, 0x18, 0x19, 0x1a -> {
                    // Literal destinations fail silently; unsupported stack
                    // operand modes are outside this subset.
                }
                default -> throw new Fault(0xfffc);
            }
        }
    }

    private int next() {
        int value = this.bus.read(this.pc) & 0xffff;
        this.pc = (this.pc + 1) & 0xffff;
        return value;
    }

    private void push(int value) {
        this.sp = (this.sp - 1) & 0xffff;
        this.bus.write(this.sp, value);
    }

    private int pop() {
        int value = this.bus.read(this.sp) & 0xffff;
        this.sp = (this.sp + 1) & 0xffff;
        return value;
    }

    private void fault(int code) {
        this.state = State.FAULTED;
        this.faultCode = code;
    }

    public void halt() {
        this.state = State.HALTED;
    }

    private static final class Fault extends RuntimeException {
        private final int code;

        private Fault(int code) {
            this.code = code;
        }
    }
}
