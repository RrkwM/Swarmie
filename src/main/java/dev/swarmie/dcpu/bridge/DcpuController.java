package dev.swarmie.dcpu.bridge;

import dev.swarmie.SwarmieEntity;
import dev.swarmie.adapter.SwarmieAdapter;
import dev.swarmie.contract.RobotId;
import dev.swarmie.dcpu.BasicMemoryBus;
import dev.swarmie.dcpu.ClockDevice;
import dev.swarmie.dcpu.Dcpu16;
import dev.swarmie.dcpu.DcpuFixtureProgram;
import dev.swarmie.dcpu.MotionDevice;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Test-only bridge from one robot entity to the pure-Java DCPU core. */
public final class DcpuController implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("swarmie-dcpu");
    private static final int CYCLE_BUDGET = 128;

    private final SwarmieEntity robot;
    private final BasicMemoryBus memory = new BasicMemoryBus();
    private final Dcpu16 cpu;
    private final MotionDevice motion;
    private final ClockDevice clock;
    private final RuntimeMotionPort port;
    private boolean closed;
    private int ticks;
    private int lastCycles;
    private int lastStatus = MotionDevice.READY;
    private int lastTerminalStatus = -1;

    public DcpuController(SwarmieEntity robot) {
        this.robot = robot;
        this.port = new RuntimeMotionPort(robot.runtime());
        this.cpu = new Dcpu16(this.memory);
        this.motion = new MotionDevice(this.port, this.cpu::queueInterrupt);
        this.clock = new ClockDevice(this::serverTick, this::vmStatus, this.cpu::pendingInterrupts);
        this.memory.map(this.motion);
        this.memory.map(this.clock);
        DcpuFixtureProgram.load(this.cpu);
        LOGGER.info("DCPU experiment attached: robot={}, budget={}, program={}",
                robot.getUUID(), CYCLE_BUDGET, DcpuFixtureProgram.PROGRAM_START);
    }

    public void tick() {
        if (this.closed || this.robot.isRemoved()) {
            return;
        }
        this.motion.tick();
        int terminalStatus = this.motion.status();
        if (terminalStatus >= MotionDevice.SUCCEEDED && terminalStatus != this.lastTerminalStatus) {
            this.lastTerminalStatus = terminalStatus;
            LOGGER.info("DCPU terminal robot={} serverTick={} motionStatus={} error={} "
                            + "commandId={} interrupts={}",
                    this.robot.getUUID(), serverTick(), terminalStatus, this.motion.error(),
                    this.motion.commandId(), this.cpu.pendingInterrupts());
        }
        Dcpu16.Result result = this.cpu.run(CYCLE_BUDGET);
        this.ticks++;
        this.lastCycles = result.cycles();
        int status = this.motion.status();
        if (status != this.lastStatus
                || status == MotionDevice.FAULTED
                || result.state() != Dcpu16.State.RUNNING) {
            LOGGER.info("DCPU tick robot={} serverTick={} cycles={} vm={} pc={} "
                            + "motionStatus={} error={} commandId={} interrupts={} "
                            + "ram1000={} ram1003={} ram1004={}",
                    this.robot.getUUID(), serverTick(), result.cycles(), result.state(),
                    result.pc(), status, this.motion.error(), this.motion.commandId(),
                    this.cpu.pendingInterrupts(),
                    this.memory.ram(DcpuFixtureProgram.STATUS_POLL),
                    this.memory.ram(DcpuFixtureProgram.TERMINAL_STATUS),
                    this.memory.ram(DcpuFixtureProgram.HANDLER_MARKER));
            this.lastStatus = status;
        }
    }

    public String snapshot() {
        return "vm=" + this.cpu.state()
                + " cycles=" + this.lastCycles
                + " totalTicks=" + this.ticks
                + " pc=" + this.cpu.pc()
                + " status=" + this.motion.status()
                + " err=" + this.motion.error()
                + " cmd=" + this.motion.commandId()
                + " interrupts=" + this.cpu.pendingInterrupts()
                + " ram1000=" + this.memory.ram(DcpuFixtureProgram.STATUS_POLL)
                + " ram1003=" + this.memory.ram(DcpuFixtureProgram.TERMINAL_STATUS)
                + " ram1004=" + this.memory.ram(DcpuFixtureProgram.HANDLER_MARKER);
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.port.stop();
        this.cpu.halt();
        SwarmieAdapter.releaseGuestMotion(new RobotId(this.robot.getUUID()));
        LOGGER.info("DCPU experiment stopped: robot={}, snapshot={}", this.robot.getUUID(), snapshot());
    }

    private long serverTick() {
        MinecraftServer server = this.robot.level().getServer();
        return server == null ? 0L : server.getTickCount();
    }

    private int vmStatus() {
        return switch (this.cpu.state()) {
            case RUNNING -> 0;
            case HALTED -> 1;
            case FAULTED -> 2;
        };
    }
}
