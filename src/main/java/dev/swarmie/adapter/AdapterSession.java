package dev.swarmie.adapter;

import dev.swarmie.SwarmieEntity;
import dev.swarmie.adapter.event.AdapterEventBus;
import dev.swarmie.adapter.event.AdapterEventStore;
import dev.swarmie.adapter.io.InboundQueue;
import dev.swarmie.adapter.registry.RobotRegistry;
import dev.swarmie.adapter.transport.LocalJsonTransport;
import dev.swarmie.contract.AckStatus;
import dev.swarmie.contract.BatchSubmission;
import dev.swarmie.contract.BatchSubmitAck;
import dev.swarmie.contract.CancelAck;
import dev.swarmie.contract.CommandResult;
import dev.swarmie.contract.CommandSpec;
import dev.swarmie.contract.CommandState;
import dev.swarmie.contract.CommandSubmission;
import dev.swarmie.contract.CommandType;
import dev.swarmie.contract.ContractLimits;
import dev.swarmie.contract.ErrorCode;
import dev.swarmie.contract.EventBatch;
import dev.swarmie.contract.RobotDescriptor;
import dev.swarmie.contract.RobotEvent;
import dev.swarmie.contract.RobotEventType;
import dev.swarmie.contract.RobotId;
import dev.swarmie.contract.RobotLifecycleState;
import dev.swarmie.contract.SubmitAck;
import dev.swarmie.contract.Vec3Dto;
import dev.swarmie.runtime.SubmissionResult;
import dev.swarmie.runtime.SwarmieCommand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** One server-session Adapter: registry, event ring and loaded-entity lookup. */
public final class AdapterSession {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdapterSession.class);
    private static final SecureRandom EPOCH_RANDOM = new SecureRandom();
    private static final AtomicLong LAST_EPOCH = new AtomicLong();
    private static final Set<CommandType> CAPABILITIES = Set.copyOf(EnumSet.allOf(CommandType.class));
    private static final int INBOUND_BUDGET = 32;
    private static final int EVENT_BATCH = 256;

    private final MinecraftServer server;
    private final long serverEpoch;
    private final RobotRegistry registry = new RobotRegistry();
    private final AdapterEventStore events;
    private final InboundQueue inbound = new InboundQueue();
    private final AdapterEventBus bus = new AdapterEventBus();
    private final Queue<CancelRequest> cancels = new ConcurrentLinkedQueue<>();
    private final AtomicInteger cancelCount = new AtomicInteger();
    private final Map<UUID, SwarmieEntity> loaded = new HashMap<>();
    private long serverTick;
    private long lastPublishedSequence;
    private boolean stopped;
    private LocalJsonTransport transport;

    public AdapterSession(MinecraftServer server) {
        this(server, nextEpoch());
    }

    public AdapterSession(MinecraftServer server, long serverEpoch) {
        if (server == null) {
            throw new IllegalArgumentException("server is required");
        }
        this.server = server;
        this.serverEpoch = serverEpoch;
        this.events = new AdapterEventStore(serverEpoch, AdapterEventStore.DEFAULT_CAPACITY);
    }

    public void start() {
        LOGGER.info("Adapter session started: epoch={}, eventCapacity={}",
                this.serverEpoch, AdapterEventStore.DEFAULT_CAPACITY);
        this.transport = LocalJsonTransport.startIfConfigured(this);
    }

    public void stop() {
        if (this.stopped) {
            return;
        }
        this.stopped = true;
        this.cancels.clear();
        this.cancelCount.set(0);
        if (this.transport != null) {
            this.transport.close();
            this.transport = null;
        }
        for (InboundQueue.Entry entry : this.inbound.drain()) {
            failInbound(entry, CommandState.INTERRUPTED, ErrorCode.INTERRUPTED,
                    "server stopping", "server stopping");
        }
        for (SwarmieEntity robot : new ArrayList<>(this.loaded.values())) {
            onEntityUnload(robot);
        }
        this.loaded.clear();
        flushEvents();
        LOGGER.info("Adapter session stopped: epoch={}", this.serverEpoch);
    }

    public long serverEpoch() {
        return this.serverEpoch;
    }

    public void onStartTick() {
        if (this.stopped) {
            return;
        }
        this.serverTick++;
        processCancels();
        int budget = INBOUND_BUDGET;
        for (InboundQueue.Entry entry : this.inbound.poll(budget)) {
            dispatch(entry);
        }
    }

    public void onEndTick() {
        if (this.stopped) {
            return;
        }
        for (SwarmieEntity robot : this.loaded.values()) {
            this.registry.refresh(
                    new RobotId(robot.getUUID()),
                    dimensionId(robot),
                    toDto(robot.position()),
                    CAPABILITIES,
                    this.serverTick);
        }
        flushEvents();
    }

    public void onEntityLoad(SwarmieEntity robot, ServerLevel level) {
        if (this.stopped || level.isClientSide()) {
            return;
        }
        RobotId id = new RobotId(robot.getUUID());
        RobotDescriptor previous = this.registry.find(id).orElse(null);
        boolean dimensionChange = this.registry.consumeDimensionChange(id);
        this.loaded.put(id.value(), robot);
        robot.runtime().setListener(new RuntimeListener(id, this));
        this.registry.load(id, dimensionId(level), toDto(robot.position()), CAPABILITIES, this.serverTick);

        String detail = dimensionChange ? "dimension change complete" : "";
        append(RobotEventType.ROBOT_LOADED, id, null, null, detail);
        LOGGER.info("Adapter robot loaded: id={}, dimension={}, previous={}, dimensionChange={}",
                id, dimensionId(level), previous == null ? "NEW" : previous.lifecycle(), dimensionChange);
    }

    public void onEntityUnload(SwarmieEntity robot) {
        if (this.stopped && this.loaded.isEmpty()) {
            return;
        }
        RobotId id = new RobotId(robot.getUUID());
        if (!this.loaded.containsKey(id.value()) && robot.isAdapterSessionEnded()) {
            return;
        }
        this.loaded.remove(id.value());
        robot.onAdapterSessionEnd("robot unloaded");
        robot.runtime().setListener(null);
        for (InboundQueue.Entry entry : this.inbound.removeRobot(id)) {
            failInbound(entry, CommandState.INTERRUPTED, ErrorCode.ROBOT_UNLOADED,
                    "robot unloaded before Runtime acceptance", "robot unloaded");
        }
        this.registry.unload(id, dimensionId(robot), toDto(robot.position()), CAPABILITIES, this.serverTick);
        append(RobotEventType.ROBOT_UNLOADED, id, null, null, "chunk unload");
        LOGGER.info("Adapter robot unloaded: id={}, dimension={}", id, dimensionId(robot));
    }

    public void onEntityRemoved(SwarmieEntity robot, Entity.RemovalReason reason) {
        RobotId id = new RobotId(robot.getUUID());
        if (reason == Entity.RemovalReason.CHANGED_DIMENSION) {
            this.loaded.remove(id.value());
            robot.onAdapterSessionEnd("dimension change");
            robot.runtime().setListener(null);
            for (InboundQueue.Entry entry : this.inbound.removeRobot(id)) {
                failInbound(entry, CommandState.INTERRUPTED, ErrorCode.INTERRUPTED,
                        "dimension change before Runtime acceptance", "dimension change");
            }
            this.registry.dimensionChanged(id, dimensionId(robot), toDto(robot.position()),
                    CAPABILITIES, this.serverTick);
            append(RobotEventType.ROBOT_DIMENSION_CHANGED, id, null, null, "dimension change");
            LOGGER.info("Adapter robot dimension change: id={}", id);
            return;
        }

        this.loaded.remove(id.value());
        robot.onAdapterSessionEnd("owner removed");
        robot.runtime().setListener(null);
        for (InboundQueue.Entry entry : this.inbound.removeRobot(id)) {
            failInbound(entry, CommandState.INTERRUPTED, ErrorCode.INTERRUPTED,
                    "robot removed before Runtime acceptance", "robot removed");
        }
        this.registry.remove(id, dimensionId(robot), toDto(robot.position()), CAPABILITIES, this.serverTick);
        append(RobotEventType.ROBOT_REMOVED, id, null, null, reason.name());
        LOGGER.info("Adapter robot removed: id={}, reason={}", id, reason);
    }

    public List<RobotDescriptor> snapshotRobots() {
        return this.registry.snapshot();
    }

    public SubmitAck submit(CommandSubmission submission) {
        if (this.stopped) {
            return SubmitAck.rejected(submission.robotId(), submission.correlationId(),
                    submission.command().type(), ErrorCode.ROBOT_UNAVAILABLE, "server adapter is not running");
        }
        // Duplicate/conflict must be resolved even if the robot is no longer
        // available; an already accepted request retains its known state.
        if (!this.inbound.containsCorrelation(submission.correlationId())) {
            Optional<RobotDescriptor> descriptor = this.registry.find(submission.robotId());
            if (descriptor.isEmpty()) {
                return SubmitAck.rejected(submission.robotId(), submission.correlationId(),
                        submission.command().type(), ErrorCode.ROBOT_NOT_FOUND, "robot is not registered");
            }
            RobotLifecycleState lifecycle = descriptor.get().lifecycle();
            if (lifecycle == RobotLifecycleState.REMOVED) {
                return SubmitAck.rejected(submission.robotId(), submission.correlationId(),
                        submission.command().type(), ErrorCode.ROBOT_REMOVED, "robot has been removed");
            }
            if (lifecycle == RobotLifecycleState.UNLOADED) {
                return SubmitAck.rejected(submission.robotId(), submission.correlationId(),
                        submission.command().type(), ErrorCode.ROBOT_NOT_LOADED, "robot is not loaded");
            }
        }
        return this.inbound.submit(submission);
    }

    public BatchSubmitAck submitBatch(BatchSubmission batch) {
        List<SubmitAck> acks = new ArrayList<>(batch.submissions().size());
        for (CommandSubmission submission : batch.submissions()) {
            acks.add(submit(submission));
        }
        return new BatchSubmitAck(acks);
    }

    public Optional<RobotDescriptor> findRobot(RobotId id) {
        return this.registry.find(id);
    }

    public CancelAck cancel(RobotId robotId, String correlationId) {
        if (this.stopped) {
            return CancelAck.rejected(robotId, correlationId, ErrorCode.ROBOT_UNAVAILABLE,
                    "server adapter is not running");
        }
        Optional<InboundQueue.Record> found = this.inbound.findRecord(correlationId);
        if (found.isEmpty()) {
            return CancelAck.unknown(robotId, correlationId);
        }
        InboundQueue.Record record = found.get();
        if (!record.submission().robotId().equals(robotId)) {
            return CancelAck.rejected(robotId, correlationId, ErrorCode.CORRELATION_CONFLICT,
                    "correlationId belongs to a different robot");
        }
        if (record.state().isTerminal()) {
            return CancelAck.alreadyTerminal(robotId, correlationId, record.state());
        }
        if (this.cancelCount.get() >= ContractLimits.MAX_CANCEL_QUEUE) {
            return CancelAck.rejected(robotId, correlationId, ErrorCode.BACKPRESSURE,
                    "cancel queue is full");
        }
        this.cancels.add(new CancelRequest(robotId, correlationId));
        this.cancelCount.incrementAndGet();
        return CancelAck.accepted(robotId, correlationId, record.state());
    }

    private void processCancels() {
        CancelRequest request;
        while ((request = this.cancels.poll()) != null) {
            this.cancelCount.decrementAndGet();
            processCancel(request);
        }
    }

    private void processCancel(CancelRequest request) {
        Optional<InboundQueue.Record> found = this.inbound.findRecord(request.correlationId());
        if (found.isEmpty()) {
            return;
        }
        InboundQueue.Record record = found.get();
        if (record.state().isTerminal()) {
            return;
        }

        Optional<InboundQueue.Entry> queued = this.inbound.removeCorrelation(request.correlationId());
        if (queued.isPresent()) {
            failInbound(queued.get(), CommandState.CANCELLED, ErrorCode.CANCELLED,
                    "cancelled before Runtime acceptance", "cancel request");
            return;
        }

        String internalId = record.internalCommandId();
        SwarmieEntity robot = this.loaded.get(request.robotId().value());
        if (internalId == null || robot == null) {
            failCorrelation(request.robotId(), request.correlationId(), internalId,
                    CommandState.CANCELLED, ErrorCode.CANCELLED, "cancelled", "cancel request");
            return;
        }
        try {
            robot.runtime().cancel(Long.parseLong(internalId));
        }
        catch (RuntimeException exception) {
            LOGGER.warn("Adapter cancel failed for {}: {}", request.correlationId(), exception.toString());
        }
    }

    private record CancelRequest(RobotId robotId, String correlationId) {
    }

    private void failCorrelation(
            RobotId robotId, String correlationId, String internalId,
            CommandState state, ErrorCode error, String message, String detail) {
        this.inbound.markTerminal(correlationId, state, error);
        CommandResult result = new CommandResult(robotId, correlationId, internalId, state, error, message);
        RobotEventType type = state == CommandState.CANCELLED
                ? RobotEventType.COMMAND_CANCELLED
                : RobotEventType.COMMAND_INTERRUPTED;
        append(type, robotId, correlationId, result, detail);
    }

    public AdapterEventBus.Subscription subscribe(Consumer<EventBatch> listener) {
        return this.bus.subscribe(listener);
    }

    public InboundQueue inbound() {
        return this.inbound;
    }

    public AdapterEventBus eventBus() {
        return this.bus;
    }

    private void dispatch(InboundQueue.Entry entry) {
        CommandSubmission submission = entry.submission();
        CommandSpec command = submission.command();
        RobotId robotId = submission.robotId();
        if (this.stopped) {
            failInbound(entry, CommandState.INTERRUPTED, ErrorCode.INTERRUPTED,
                    "server stopping", "server stopping");
            return;
        }
        SwarmieEntity robot = this.loaded.get(robotId.value());
        if (robot == null || robot.isAdapterSessionEnded()) {
            failInbound(entry, CommandState.INTERRUPTED, ErrorCode.ROBOT_UNLOADED,
                    "robot is not loaded", "robot unavailable");
            return;
        }

        append(RobotEventType.COMMAND_ENQUEUED, robotId, submission.correlationId(), null, "");
        SubmissionResult result;
        try {
            result = switch (command) {
                case CommandSpec.TimedFlight flight -> robot.runtime().submitTimedFlight(
                        new Vec3(flight.velocity().x(), flight.velocity().y(), flight.velocity().z()),
                        flight.durationTicks(),
                        submission.correlationId());
                case CommandSpec.Stop stop -> robot.runtime().submitStop(submission.correlationId());
                case CommandSpec.MineBlock mine -> robot.runtime().submitMineBlock(
                        new BlockPos(mine.position().x(), mine.position().y(), mine.position().z()),
                        submission.correlationId());
                case CommandSpec.UseItemOnBlock use -> robot.runtime().submitUseItemOn(
                        new BlockPos(use.position().x(), use.position().y(), use.position().z()),
                        Direction.valueOf(use.face().name()),
                        new Vec3(use.hit().x(), use.hit().y(), use.hit().z()),
                        submission.correlationId());
            };
        }
        catch (RuntimeException exception) {
            failInbound(entry, CommandState.FAILED, ErrorCode.INTERNAL_ERROR,
                    "command dispatch failed: " + exception.getMessage(), "dispatch exception");
            return;
        }

        if (!result.accepted()) {
            failInbound(entry, CommandState.FAILED, mapRuntimeError(result.error()),
                    result.error(), "runtime rejected inbox command");
            return;
        }
        this.inbound.markRuntimeAccepted(submission.correlationId(), Long.toString(result.commandId()));
    }

    private void failInbound(
            InboundQueue.Entry entry,
            CommandState state,
            ErrorCode error,
            String message,
            String detail) {
        CommandSubmission submission = entry.submission();
        this.inbound.markTerminal(submission.correlationId(), state, error);
        CommandResult result = new CommandResult(
                submission.robotId(),
                submission.correlationId(),
                entry.record().internalCommandId(),
                state,
                error,
                message);
        RobotEventType type = switch (state) {
            case FAILED -> RobotEventType.COMMAND_FAILED;
            case CANCELLED -> RobotEventType.COMMAND_CANCELLED;
            case INTERRUPTED -> RobotEventType.COMMAND_INTERRUPTED;
            default -> RobotEventType.COMMAND_FAILED;
        };
        append(type, submission.robotId(), submission.correlationId(), result, detail);
    }

    private static ErrorCode mapRuntimeError(String error) {
        if (error == null) {
            return ErrorCode.EXECUTION_FAILED;
        }
        if (error.contains("full")) {
            return ErrorCode.QUEUE_FULL;
        }
        if (error.contains("terminated") || error.contains("removed")) {
            return ErrorCode.ROBOT_UNLOADED;
        }
        return ErrorCode.EXECUTION_FAILED;
    }

    private void flushEvents() {
        while (!this.stopped || this.events.size() > 0) {
            EventBatch batch = this.events.readAfter(this.lastPublishedSequence, EVENT_BATCH);
            if (batch.events().isEmpty()) {
                return;
            }
            this.bus.publish(batch);
            this.lastPublishedSequence = batch.nextSequence() - 1L;
            if (batch.events().size() < EVENT_BATCH) {
                return;
            }
        }
    }

    public EventBatch readEvents(long afterSequence, int maxEvents) {
        return this.events.readAfter(afterSequence, maxEvents);
    }

    public MinecraftServer server() {
        return this.server;
    }

    public long serverTick() {
        return this.serverTick;
    }

    public RobotRegistry registry() {
        return this.registry;
    }

    public AdapterEventStore eventStore() {
        return this.events;
    }

    private void append(
            RobotEventType type, RobotId robotId, String correlationId, CommandResult result, String detail) {
        RobotEvent event = this.events.append(type, robotId, correlationId, result, detail, this.serverTick);
        LOGGER.debug("Adapter event {} {} robot={} correlation={}", event.sequence(), type, robotId, correlationId);
    }

    private static long nextEpoch() {
        long value;
        do {
            value = EPOCH_RANDOM.nextLong() & Long.MAX_VALUE;
        }
        while (value == 0L || value == LAST_EPOCH.get());
        LAST_EPOCH.set(value);
        return value;
    }

    private static String dimensionId(SwarmieEntity robot) {
        return dimensionId(robot.level());
    }

    private static String dimensionId(net.minecraft.world.level.Level level) {
        return level.dimension().identifier().toString();
    }

    private static Vec3Dto toDto(net.minecraft.world.phys.Vec3 vector) {
        return new Vec3Dto(vector.x, vector.y, vector.z);
    }

    private static final class RuntimeListener implements dev.swarmie.runtime.SwarmieRuntime.Listener {
        private final RobotId robotId;
        private final AdapterSession session;

        private RuntimeListener(RobotId robotId, AdapterSession session) {
            this.robotId = robotId;
            this.session = session;
        }

        @Override
        public void onCommandAccepted(SwarmieCommand command) {
            this.session.append(
                    RobotEventType.COMMAND_ACCEPTED,
                    this.robotId,
                    command.getCorrelationId(),
                    result(command, CommandState.QUEUED, null),
                    "");
        }

        @Override
        public void onCommandStarted(SwarmieCommand command) {
            this.session.append(
                    RobotEventType.COMMAND_STARTED,
                    this.robotId,
                    command.getCorrelationId(),
                    result(command, CommandState.RUNNING, null),
                    "");
        }

        @Override
        public void onCommandTerminal(SwarmieCommand command) {
            CommandState state = toState(command.getState());
            ErrorCode error = toError(state);
            this.session.inbound.markTerminal(command.getCorrelationId(), state, error);
            RobotEventType type = switch (state) {
                case SUCCEEDED -> RobotEventType.COMMAND_COMPLETED;
                case FAILED -> RobotEventType.COMMAND_FAILED;
                case CANCELLED -> RobotEventType.COMMAND_CANCELLED;
                case INTERRUPTED -> RobotEventType.COMMAND_INTERRUPTED;
                default -> RobotEventType.UNKNOWN;
            };
            this.session.append(
                    type,
                    this.robotId,
                    command.getCorrelationId(),
                    result(command, state, error),
                    command.getResult());
        }

        private CommandResult result(SwarmieCommand command, CommandState state, ErrorCode error) {
            return new CommandResult(
                    this.robotId,
                    command.getCorrelationId(),
                    Long.toString(command.getId()),
                    state,
                    error,
                    command.getResult());
        }

        private static CommandState toState(SwarmieCommand.State state) {
            return switch (state) {
                case QUEUED -> CommandState.QUEUED;
                case RUNNING -> CommandState.RUNNING;
                case SUCCEEDED -> CommandState.SUCCEEDED;
                case FAILED -> CommandState.FAILED;
                case CANCELLED -> CommandState.CANCELLED;
                case INTERRUPTED -> CommandState.INTERRUPTED;
            };
        }

        private static ErrorCode toError(CommandState state) {
            return switch (state) {
                case SUCCEEDED, QUEUED, RUNNING -> null;
                case FAILED -> ErrorCode.EXECUTION_FAILED;
                case CANCELLED -> ErrorCode.CANCELLED;
                case INTERRUPTED -> ErrorCode.INTERRUPTED;
            };
        }
    }
}
