package dev.swarmie.dcpu;

/**
 * One hand-assembled DCPU-16 1.7 fixture image. It is not an assembler.
 *
 * <p>The program sets the motion device's duration/velocity, starts a timed
 * flight, polls status/tick/VM registers into RAM, and handles the terminal
 * interrupt. The handler acknowledges the latched motion status and leaves a
 * marker at 0x1004.
 */
public final class DcpuFixtureProgram {
    public static final int PROGRAM_START = 0x0000;
    public static final int HANDLER_ADDRESS = 0x001d;
    public static final int POLL_ADDRESS = 0x000f;
    public static final int STATUS_POLL = 0x1000;
    public static final int TICK_LOW = 0x1001;
    public static final int VM_STATUS = 0x1002;
    public static final int TERMINAL_STATUS = 0x1003;
    public static final int HANDLER_MARKER = 0x1004;
    public static final int HANDLER_MARKER_VALUE = 0x1234;

    private DcpuFixtureProgram() {
    }

    public static int[] words() {
        int[] words = new int[42];
        int at = 0;
        at = setAImmediate(words, at, HANDLER_ADDRESS); // 0,1
        words[at++] = special(0x0a, Dcpu16.REG_A);      // 2 IAS A
        at = setAImmediate(words, at, 20);              // 3,4
        at = setMemoryFromA(words, at, MotionDevice.DURATION); // 5,6
        at = setAImmediate(words, at, 50);              // 7,8 0.050 blocks/tick
        at = setMemoryFromA(words, at, MotionDevice.VX); // 9,10
        at = setAImmediate(words, at, 1);               // 11,12
        at = setMemoryFromA(words, at, MotionDevice.CMD); // 13,14 start
        at = setAFromMemory(words, at, MotionDevice.STAT); // 15,16
        at = setMemoryFromA(words, at, STATUS_POLL);     // 17,18
        at = setAFromMemory(words, at, ClockDevice.START); // 19,20
        at = setMemoryFromA(words, at, TICK_LOW);        // 21,22
        at = setAFromMemory(words, at, ClockDevice.START + 2); // 23,24
        at = setMemoryFromA(words, at, VM_STATUS);       // 25,26
        at = setPC(words, at, POLL_ADDRESS);             // 27,28
        at = setAFromMemory(words, at, MotionDevice.STAT); // 29,30 handler
        at = setMemoryFromA(words, at, TERMINAL_STATUS); // 31,32
        at = setAImmediate(words, at, MotionDevice.CMD_ACK); // 33,34
        at = setMemoryFromA(words, at, MotionDevice.CMD); // 35,36 ack
        at = setAImmediate(words, at, HANDLER_MARKER_VALUE); // 37,38
        at = setMemoryFromA(words, at, HANDLER_MARKER);  // 39,40
        words[at] = special(0x0b, 0);                    // 41 RFI
        return words;
    }

    public static void load(Dcpu16 cpu) {
        cpu.loadProgram(PROGRAM_START, words());
    }

    private static int setAImmediate(int[] words, int at, int value) {
        words[at++] = basic(0x01, 0x1f, Dcpu16.REG_A);
        words[at++] = value & 0xffff;
        return at;
    }

    private static int setAFromMemory(int[] words, int at, int address) {
        words[at++] = basic(0x01, 0x1e, Dcpu16.REG_A);
        words[at++] = address & 0xffff;
        return at;
    }

    private static int setMemoryFromA(int[] words, int at, int address) {
        words[at++] = basic(0x01, Dcpu16.REG_A, 0x1e);
        words[at++] = address & 0xffff;
        return at;
    }

    private static int setPC(int[] words, int at, int address) {
        words[at++] = basic(0x01, 0x1f, 0x1c);
        words[at++] = address & 0xffff;
        return at;
    }

    private static int basic(int opcode, int a, int b) {
        return ((a & 0x3f) << 10) | ((b & 0x1f) << 5) | (opcode & 0x1f);
    }

    private static int special(int opcode, int a) {
        return ((a & 0x3f) << 10) | ((opcode & 0x1f) << 5);
    }
}
