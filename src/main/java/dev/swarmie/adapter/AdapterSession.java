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
import dev.swarmie.contract.BlockObservation;
import dev.swarmie.contract.BlockPosDto;
import dev.swarmie.contract.BlockSnapshotDto;
import dev.swarmie.contract.CancelAck;
import dev.swarmie.contract.CommandResult;
import dev.swarmie.contract.CommandSpec;
import dev.swarmie.contract.CommandState;
import dev.swarmie.contract.CommandSubmission;
import dev.swarmie.contract.CommandType;
import dev.swarmie.contract.ContractLimits;
import dev.swarmie.contract.ErrorCode;
import dev.swarmie.contract.EventBatch;
import dev.swarmie.contract.InventorySlot;
import dev.swarmie.contract.InventoryState;
import dev.swarmie.contract.ObservationStatus;
import dev.swarmie.contract.RobotDescriptor;
import dev.swarmie.contract.RobotEvent;
import dev.swarmie.contract.RobotEventType;
import dev.swarmie.contract.RobotId;
import dev.swarmie.contract.RobotLifecycleState;
import dev.swarmie.contract.RobotState;
import dev.swarmie.contract.SetpointAck;
import dev.swarmie.contract.SubmitAck;
import dev.swarmie.contract.Vec3Dto;
import dev.swarmie.query.BlockQueryResult;
import dev.swarmie.query.BlockSnapshot;
import dev.swarmie.query.QueryStatus;
import dev.swarmie.runtime.SubmissionResult;
import dev.swarmie.runtime.SwarmieCommand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
    private final VelocitySetpoints velocitySetpoints = new VelocitySetpoints();
    private final RobotStateStore states = new RobotStateStore();
    private final RobotInventoryStore inventories = new RobotInventoryStore();
    private final Queue<BlockRequest> blockRequests = new ConcurrentLinkedQueue<>();
    private volatile long serverTick;
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
        while (!this.blockRequests.isEmpty()) {
            BlockRequest request = this.blockRequests.poll();
            if (request != null) {
                request.result().complete(BlockObservationResult.failure(
                        ErrorCode.ROBOT_UNAVAILABLE, "server adapter is not running"));
            }
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
        applyVelocitySetpoints();
        processBlockRequests();
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
        publishStates();
        publishInventories();
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
        this.velocitySetpoints.forget(id.value(), robot);
        this.states.put(toState(robot));
        this.inventories.put(toInventoryState(robot));

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
        this.velocitySetpoints.forget(id.value(), robot);
        this.states.remove(id);
        this.inventories.remove(id);
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
            this.velocitySetpoints.forget(id.value(), robot);
            this.states.remove(id);
            this.inventories.remove(id);
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
        this.velocitySetpoints.forget(id.value(), robot);
        this.states.remove(id);
        this.inventories.remove(id);
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

    /** Latest completed/known self-state snapshot for a loaded robot. */
    public Optional<RobotState> readState(RobotId robotId) {
        return this.states.find(robotId);
    }

    /** Latest end-tick inventory snapshot for a loaded robot. */
    public Optional<InventoryState> readInventory(RobotId robotId) {
        return this.inventories.find(robotId);
    }

    /**
     * Queues a read-only block observation on the server thread. The future
     * completes on the server tick; callers must not block the server thread.
     */
    public CompletableFuture<BlockObservationResult> requestBlockObservation(
            RobotId robotId, BlockPosDto position) {
        if (this.stopped) {
            return CompletableFuture.completedFuture(BlockObservationResult.failure(
                    ErrorCode.ROBOT_UNAVAILABLE, "server adapter is not running"));
        }
        Optional<RobotDescriptor> descriptor = this.registry.find(robotId);
        if (descriptor.isEmpty()) {
            return CompletableFuture.completedFuture(BlockObservationResult.failure(
                    ErrorCode.ROBOT_NOT_FOUND, "robot is not registered"));
        }
        RobotLifecycleState lifecycle = descriptor.get().lifecycle();
        if (lifecycle == RobotLifecycleState.REMOVED) {
            return CompletableFuture.completedFuture(BlockObservationResult.failure(
                    ErrorCode.ROBOT_REMOVED, "robot has been removed"));
        }
        if (lifecycle == RobotLifecycleState.UNLOADED) {
            return CompletableFuture.completedFuture(BlockObservationResult.failure(
                    ErrorCode.ROBOT_NOT_LOADED, "robot is not loaded"));
        }
        if (position == null) {
            return CompletableFuture.completedFuture(BlockObservationResult.failure(
                    ErrorCode.INVALID_ARGUMENT, "block position is required"));
        }
        CompletableFuture<BlockObservationResult> future = new CompletableFuture<>();
        this.blockRequests.add(new BlockRequest(robotId, position, future));
        return future;
    }

    private void processBlockRequests() {
        for (int i = 0; i < 32; i++) {
            BlockRequest request = this.blockRequests.poll();
            if (request == null) {
                return;
            }
            if (request.result().isDone()) {
                continue;
            }
            request.result().complete(readBlockObservation(request.robotId(), request.position()));
        }
    }

    private BlockObservationResult readBlockObservation(RobotId robotId, BlockPosDto position) {
        Optional<RobotDescriptor> descriptor = this.registry.find(robotId);
        if (descriptor.isEmpty()) {
            return BlockObservationResult.failure(ErrorCode.ROBOT_NOT_FOUND, "robot is not registered");
        }
        RobotLifecycleState lifecycle = descriptor.get().lifecycle();
        if (lifecycle == RobotLifecycleState.REMOVED) {
            return BlockObservationResult.failure(ErrorCode.ROBOT_REMOVED, "robot has been removed");
        }
        if (lifecycle == RobotLifecycleState.UNLOADED) {
            return BlockObservationResult.failure(ErrorCode.ROBOT_NOT_LOADED, "robot is not loaded");
        }
        SwarmieEntity robot = this.loaded.get(robotId.value());
        if (robot == null) {
            return BlockObservationResult.failure(ErrorCode.ROBOT_NOT_LOADED, "robot is not loaded");
        }
        try {
            BlockQueryResult result = robot.queryBlock(new BlockPos(position.x(), position.y(), position.z()));
            ObservationStatus status = observationStatus(result.status());
            BlockSnapshotDto snapshot = result.successful() && result.block() != null
                    ? toBlockSnapshot(result.block())
                    : null;
            if (status == ObservationStatus.OK && snapshot == null) {
                status = ObservationStatus.ERROR;
            }
            BlockObservation observation = new BlockObservation(
                    robotId, position, status, snapshot,
                    status == ObservationStatus.OK ? "" : result.reason(),
                    this.serverEpoch, this.serverTick);
            return BlockObservationResult.success(observation);
        }
        catch (RuntimeException exception) {
            return BlockObservationResult.failure(ErrorCode.INTERNAL_ERROR, exception.getMessage());
        }
    }

    private static ObservationStatus observationStatus(QueryStatus status) {
        return switch (status) {
            case OK -> ObservationStatus.OK;
            case UNLOADED -> ObservationStatus.UNLOADED;
            case OUT_OF_BOUNDS -> ObservationStatus.OUT_OF_BOUNDS;
            case INVALID -> ObservationStatus.INVALID;
            case UNSUPPORTED -> ObservationStatus.UNSUPPORTED;
            case WRONG_THREAD, ERROR -> ObservationStatus.ERROR;
        };
    }

    private static BlockSnapshotDto toBlockSnapshot(BlockSnapshot snapshot) {
        return new BlockSnapshotDto(
                new BlockPosDto(snapshot.pos().getX(), snapshot.pos().getY(), snapshot.pos().getZ()),
                snapshot.blockId().toString(), snapshot.properties(), snapshot.air());
    }

    private InventoryState toInventoryState(SwarmieEntity robot) {
        List<InventorySlot> slots = new ArrayList<>(InventoryState.SLOT_COUNT);
        for (int i = 0; i < InventoryState.SLOT_COUNT; i++) {
            ItemStack stack = robot.inventory().getItem(i);
            if (stack.isEmpty()) {
                slots.add(new InventorySlot(i, null, 0));
            }
            else {
                Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                slots.add(new InventorySlot(i,
                        itemId == null ? "minecraft:unknown" : itemId.toString(),
                        stack.getCount()));
            }
        }
        return new InventoryState(new RobotId(robot.getUUID()), this.serverEpoch,
                this.serverTick, robot.inventory().getSelectedSlot(), slots);
    }

    private void publishInventories() {
        Set<UUID> live = new HashSet<>();
        for (SwarmieEntity robot : this.loaded.values()) {
            this.inventories.put(toInventoryState(robot));
            live.add(robot.getUUID());
        }
        this.inventories.retainOnly(live);
    }

    public record BlockObservationResult(BlockObservation observation, ErrorCode error, String message) {
        public static BlockObservationResult success(BlockObservation observation) {
            return new BlockObservationResult(observation, null, "");
        }

        public static BlockObservationResult failure(ErrorCode error, String message) {
            return new BlockObservationResult(null, error, message == null ? "" : message);
        }

        public boolean failed() {
            return this.error != null;
        }
    }

    private record BlockRequest(RobotId robotId, BlockPosDto position,
                                CompletableFuture<BlockObservationResult> result) {
    }

    /**
     * Accepts, replaces or clears the velocity setpoint. This is deliberately
     * not a Runtime command: it never enters the action FIFO.
     */
    public SetpointAck requestVelocitySetpoint(RobotId robotId, Vec3Dto velocity, boolean clear) {
        if (this.stopped) {
            return SetpointAck.rejected(robotId, this.velocitySetpoints.revision(robotId), false,
                    ErrorCode.ROBOT_UNAVAILABLE, "server adapter is not running",
                    this.serverEpoch, this.serverTick);
        }
        Optional<RobotDescriptor> descriptor = this.registry.find(robotId);
        if (descriptor.isEmpty()) {
            return SetpointAck.rejected(robotId, 0L, false,
                    ErrorCode.ROBOT_NOT_FOUND, "robot is not registered",
                    this.serverEpoch, this.serverTick);
        }
        RobotLifecycleState lifecycle = descriptor.get().lifecycle();
        if (lifecycle == RobotLifecycleState.REMOVED) {
            return SetpointAck.rejected(robotId, this.velocitySetpoints.revision(robotId), false,
                    ErrorCode.ROBOT_REMOVED, "robot has been removed",
                    this.serverEpoch, this.serverTick);
        }
        if (lifecycle == RobotLifecycleState.UNLOADED) {
            return SetpointAck.rejected(robotId, this.velocitySetpoints.revision(robotId), false,
                    ErrorCode.ROBOT_NOT_LOADED, "robot is not loaded",
                    this.serverEpoch, this.serverTick);
        }
        if (!clear && velocity == null) {
            return SetpointAck.rejected(robotId, this.velocitySetpoints.revision(robotId),
                    this.velocitySetpoints.isSetpointActive(robotId),
                    ErrorCode.INVALID_VECTOR, "velocity is required",
                    this.serverEpoch, this.serverTick);
        }
        return clear
                ? this.velocitySetpoints.clear(robotId, this.serverEpoch, this.serverTick)
                : this.velocitySetpoints.set(robotId, velocity, this.serverEpoch, this.serverTick);
    }

    private void applyVelocitySetpoints() {
        for (SwarmieEntity robot : this.loaded.values()) {
            VelocitySetpoints.Frame frame = this.velocitySetpoints.frame(robot.getUUID());
            if (frame.clear()) {
                robot.stopFlight();
            }
            if (frame.velocity() != null) {
                robot.setDesiredVelocity(new Vec3(
                        frame.velocity().x(), frame.velocity().y(), frame.velocity().z()));
            }
        }
    }

    private void publishStates() {
        Set<UUID> live = new HashSet<>();
        for (SwarmieEntity robot : this.loaded.values()) {
            this.states.put(toState(robot));
            live.add(robot.getUUID());
        }
        this.states.retainOnly(live);
    }

    private RobotState toState(SwarmieEntity robot) {
        return new RobotState(
                new RobotId(robot.getUUID()),
                this.serverEpoch,
                this.serverTick,
                dimensionId(robot),
                toDto(robot.position()),
                toDto(robot.getVelocity()),
                robot.getYRot(),
                robot.getXRot(),
                RobotLifecycleState.LOADED);
    }

    private static boolean isLegacyMotion(CommandType type) {
        return type == CommandType.TIMED_FLIGHT || type == CommandType.STOP;
    }

    private static boolean isLegacyMotion(SwarmieCommand.Type type) {
        return type == SwarmieCommand.Type.TIMED_FLIGHT || type == SwarmieCommand.Type.STOP;
    }

    /** Releases one legacy motion ownership claim for this terminal command. */
    void onLegacyMotionTerminal(RobotId robotId, SwarmieCommand command) {
        if (isLegacyMotion(command.getType())) {
            this.velocitySetpoints.releaseLegacy(robotId);
        }
    }

    public SubmitAck submit(CommandSubmission submission) {
        if (this.stopped) {
            return SubmitAck.rejected(submission.robotId(), submission.correlationId(),
                    submission.command().type(), ErrorCode.ROBOT_UNAVAILABLE, "server adapter is not running");
        }
        // Duplicate/conflict must be resolved even if the robot is no longer
        // available; an already accepted request retains its known state.
        boolean newCorrelation = !this.inbound.containsCorrelation(submission.correlationId());
        if (newCorrelation) {
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

        boolean claimedLegacy = false;
        if (newCorrelation && isLegacyMotion(submission.command().type())) {
            if (!this.velocitySetpoints.claimLegacy(submission.robotId())) {
                return SubmitAck.rejected(submission.robotId(), submission.correlationId(),
                        submission.command().type(), ErrorCode.MOTION_CHANNEL_BUSY,
                        "a velocity setpoint owns the motion channel; clear it before TimedFlight/Stop");
            }
            claimedLegacy = true;
        }

        SubmitAck ack = this.inbound.submit(submission);
        if (claimedLegacy
                && (ack.status() == AckStatus.REJECTED || ack.status() == AckStatus.DUPLICATE)) {
            this.velocitySetpoints.releaseLegacy(submission.robotId());
        }
        return ack;
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
        this.inbound.findRecord(correlationId).ifPresent(record -> {
            if (isLegacyMotion(record.submission().command().type())) {
                this.velocitySetpoints.releaseLegacy(robotId);
            }
        });
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
        if (isLegacyMotion(submission.command().type())) {
            this.velocitySetpoints.releaseLegacy(submission.robotId());
        }
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
            this.session.onLegacyMotionTerminal(this.robotId, command);
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
