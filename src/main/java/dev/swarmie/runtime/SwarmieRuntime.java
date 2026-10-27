package dev.swarmie.runtime;

import dev.swarmie.interaction.InteractionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Per-robot command Runtime.
 *
 * <p>Owns one FIFO queue, at most one active long-running command, and a bounded
 * result history. It is driven exclusively from the owning entity's server tick.
 * It performs no world or physics mutation itself: commands call the narrow
 * {@link FlightTarget} API implemented by {@code SwarmieEntity}.
 */
public final class SwarmieRuntime {
    public static final int MAX_QUEUE_SIZE = 16;
    public static final int MAX_HISTORY_SIZE = 32;
    public static final int MAX_DURATION_TICKS = 6000;
    public static final double STOP_SPEED_THRESHOLD = 0.01D;

    private static final Logger LOGGER = LoggerFactory.getLogger(SwarmieRuntime.class);

    /** Adapter observation hook; notifications must not change command behavior. */
    public interface Listener {
        default void onCommandAccepted(SwarmieCommand command) {
        }

        default void onCommandStarted(SwarmieCommand command) {
        }

        default void onCommandTerminal(SwarmieCommand command) {
        }
    }

    private final FlightTarget target;
    private final InteractionHandler interactionHandler;
    private final Deque<SwarmieCommand> queue = new ArrayDeque<>();
    private final Deque<SwarmieCommand> history = new ArrayDeque<>();

    private SwarmieCommand active;
    private long nextCommandId = 1L;
    private long tickCounter;
    private boolean terminated;
    private Listener listener;

    public SwarmieRuntime(FlightTarget target) {
        this(target, null);
    }

    public SwarmieRuntime(FlightTarget target, InteractionHandler interactionHandler) {
        if (target == null) {
            throw new NullPointerException("target");
        }
        this.target = target;
        this.interactionHandler = interactionHandler;
    }

    // ------------------------------------------------------------------ API

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public SubmissionResult submitTimedFlight(Vec3 velocity, int durationTicks) {
        return submitTimedFlight(velocity, durationTicks, null);
    }

    public SubmissionResult submitTimedFlight(Vec3 velocity, int durationTicks, String correlationId) {
        if (this.terminated || this.target.isRemoved()) {
            return SubmissionResult.rejected("runtime terminated or owner removed");
        }
        if (velocity == null || !isFinite(velocity)) {
            return SubmissionResult.rejected("velocity must be finite");
        }
        if (durationTicks <= 0 || durationTicks > MAX_DURATION_TICKS) {
            return SubmissionResult.rejected("duration must be between 1 and " + MAX_DURATION_TICKS + " ticks");
        }
        return enqueue(new TimedFlightCommand(this.nextCommandId++, this.tickCounter, velocity, durationTicks),
                correlationId);
    }

    public SubmissionResult submitStop() {
        return submitStop(null);
    }

    public SubmissionResult submitStop(String correlationId) {
        if (this.terminated || this.target.isRemoved()) {
            return SubmissionResult.rejected("runtime terminated or owner removed");
        }
        return enqueue(new StopCommand(this.nextCommandId++, this.tickCounter), correlationId);
    }

    public SubmissionResult submitMineBlock(BlockPos pos) {
        return submitMineBlock(pos, null);
    }

    public SubmissionResult submitMineBlock(BlockPos pos, String correlationId) {
        if (this.terminated || this.target.isRemoved()) {
            return SubmissionResult.rejected("runtime terminated or owner removed");
        }
        if (this.interactionHandler == null) {
            return SubmissionResult.rejected("interaction is not available");
        }
        if (pos == null) {
            return SubmissionResult.rejected("block position is required");
        }
        return enqueue(new MineBlockCommand(this.nextCommandId++, this.tickCounter, this.interactionHandler, pos),
                correlationId);
    }

    public SubmissionResult submitUseItemOn(BlockPos pos, Direction face, Vec3 hit) {
        return submitUseItemOn(pos, face, hit, null);
    }

    public SubmissionResult submitUseItemOn(BlockPos pos, Direction face, Vec3 hit, String correlationId) {
        if (this.terminated || this.target.isRemoved()) {
            return SubmissionResult.rejected("runtime terminated or owner removed");
        }
        if (this.interactionHandler == null) {
            return SubmissionResult.rejected("interaction is not available");
        }
        if (pos == null || face == null || hit == null) {
            return SubmissionResult.rejected("position, face and hit position are required");
        }
        if (!Double.isFinite(hit.x) || !Double.isFinite(hit.y) || !Double.isFinite(hit.z)) {
            return SubmissionResult.rejected("hit position must be finite");
        }
        return enqueue(new UseItemOnBlockCommand(
                this.nextCommandId++, this.tickCounter, this.interactionHandler, pos, face, hit), correlationId);
    }

    public Optional<SwarmieCommand> getCommand(long id) {
        if (this.active != null && this.active.getId() == id) {
            return Optional.of(this.active);
        }
        for (SwarmieCommand command : this.queue) {
            if (command.getId() == id) {
                return Optional.of(command);
            }
        }
        for (SwarmieCommand command : this.history) {
            if (command.getId() == id) {
                return Optional.of(command);
            }
        }
        return Optional.empty();
    }

    public Optional<SwarmieCommand> getCurrentCommand() {
        return Optional.ofNullable(this.active);
    }

    public List<SwarmieCommand> getQueuedCommands() {
        return List.copyOf(this.queue);
    }

    /** Retained terminal commands, oldest first. */
    public List<SwarmieCommand> getHistory() {
        return List.copyOf(this.history);
    }

    public boolean cancel(long id) {
        if (this.terminated) {
            return false;
        }

        if (this.active != null && this.active.getId() == id) {
            cancelActiveCommand();
            return true;
        }

        Iterator<SwarmieCommand> iterator = this.queue.iterator();
        while (iterator.hasNext()) {
            SwarmieCommand command = iterator.next();
            if (command.getId() == id) {
                iterator.remove();
                command.cancel(this.tickCounter, "cancelled while queued");
                remember(command);
                notifyTerminal(command);
                return true;
            }
        }
        return false;
    }

    public boolean cancelActive() {
        if (this.terminated || this.active == null) {
            return false;
        }
        cancelActiveCommand();
        return true;
    }

    public int cancelAll() {
        if (this.terminated) {
            return 0;
        }
        int count = 0;
        if (this.active != null) {
            cancelActiveCommand();
            count++;
        }
        while (!this.queue.isEmpty()) {
            SwarmieCommand command = this.queue.pollFirst();
            command.cancel(this.tickCounter, "cancelled all commands");
            remember(command);
            notifyTerminal(command);
            count++;
        }
        return count;
    }

    public boolean isTerminated() {
        return this.terminated;
    }

    // -------------------------------------------------------------- lifecycle

    /**
     * Advances one server tick. At most one active command is processed and at
     * most one new command is started; exceptions become FAILED results and do
     * not stop future ticks.
     */
    public void tick() {
        if (this.terminated) {
            return;
        }
        if (this.target.isRemoved()) {
            onOwnerRemoved();
            return;
        }

        this.tickCounter++;

        if (this.active != null) {
            try {
                String result = this.active.tick(this.target);
                if (result != null) {
                    this.active.succeed(this.tickCounter, result);
                }
            }
            catch (Exception exception) {
                fail(this.active, exception);
            }

            if (this.active != null && this.active.getState().isTerminal()) {
                complete(this.active);
            }
        }

        if (this.active == null && !this.queue.isEmpty()) {
            SwarmieCommand next = this.queue.pollFirst();
            next.markRunning(this.tickCounter);
            notifyStarted(next);
            try {
                // Start only. The command gets its first tick on the next server
                // tick, so a timed flight's duration counts real applied ticks.
                next.start(this.target);
            }
            catch (Exception exception) {
                fail(next, exception);
            }

            if (next.getState().isTerminal()) {
                complete(next);
            }
            else {
                this.active = next;
            }
        }
    }

    /**
     * Called when the owning entity is permanently removed (not for ordinary
     * chunk unload). Unfinished work becomes INTERRUPTED.
     */
    public void onOwnerRemoved() {
        onOwnerUnloaded("owner removed");
    }

    /** Called when the owner leaves the loaded world; unfinished work is INTERRUPTED. */
    public void onOwnerUnloaded(String reason) {
        if (this.terminated) {
            return;
        }
        this.terminated = true;
        String detail = reason == null || reason.isBlank() ? "owner unloaded" : reason;

        if (this.active != null) {
            this.active.interrupt(this.tickCounter, detail);
            remember(this.active);
            notifyTerminal(this.active);
            this.active = null;
        }
        while (!this.queue.isEmpty()) {
            SwarmieCommand command = this.queue.pollFirst();
            command.interrupt(this.tickCounter, detail + " while queued");
            remember(command);
            notifyTerminal(command);
        }
    }

    // ---------------------------------------------------------------- internal

    private SubmissionResult enqueue(SwarmieCommand command) {
        return enqueue(command, null);
    }

    private SubmissionResult enqueue(SwarmieCommand command, String correlationId) {
        if (this.queue.size() >= MAX_QUEUE_SIZE) {
            return SubmissionResult.rejected("command queue is full (" + MAX_QUEUE_SIZE + ")");
        }
        command.setCorrelationId(correlationId);
        this.queue.addLast(command);
        notifyAccepted(command);
        return SubmissionResult.accepted(command.getId());
    }

    private void cancelActiveCommand() {
        SwarmieCommand command = this.active;
        if (command == null) {
            return;
        }
        try {
            command.cancelInternal(this.target);
        }
        catch (Exception exception) {
            LOGGER.error("Swarmie command {} failed while cancelling", command.getId(), exception);
        }
        command.cancel(this.tickCounter, "cancelled while active");
        complete(command);
    }

    private void fail(SwarmieCommand command, Exception exception) {
        if (exception instanceof CommandFailureException) {
            String message = exception.getMessage() == null ? "interaction failed" : exception.getMessage();
            LOGGER.warn("Swarmie command {} ({}) failed: {}", command.getId(), command.getType(), message);
            if (!command.getState().isTerminal()) {
                command.fail(this.tickCounter, message);
            }
            return;
        }

        String message = exception.getClass().getSimpleName();
        if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
            message += ": " + exception.getMessage();
        }
        LOGGER.error("Swarmie command {} ({}) failed", command.getId(), command.getType(), exception);
        if (!command.getState().isTerminal()) {
            command.fail(this.tickCounter, message);
        }
    }

    private void complete(SwarmieCommand command) {
        if (command == this.active) {
            this.active = null;
        }
        if (command.getState().isTerminal()) {
            remember(command);
            notifyTerminal(command);
        }
    }

    private void notifyAccepted(SwarmieCommand command) {
        if (this.listener == null) {
            return;
        }
        try {
            this.listener.onCommandAccepted(command);
        }
        catch (RuntimeException exception) {
            LOGGER.error("Adapter listener failed for accepted command {}", command.getId(), exception);
        }
    }

    private void notifyStarted(SwarmieCommand command) {
        if (this.listener == null) {
            return;
        }
        try {
            this.listener.onCommandStarted(command);
        }
        catch (RuntimeException exception) {
            LOGGER.error("Adapter listener failed for started command {}", command.getId(), exception);
        }
    }

    private void notifyTerminal(SwarmieCommand command) {
        if (this.listener == null || !command.markTerminalNotified()) {
            return;
        }
        try {
            this.listener.onCommandTerminal(command);
        }
        catch (RuntimeException exception) {
            LOGGER.error("Adapter listener failed for terminal command {}", command.getId(), exception);
        }
    }

    private void remember(SwarmieCommand command) {
        this.history.addLast(command);
        while (this.history.size() > MAX_HISTORY_SIZE) {
            this.history.removeFirst();
        }
    }

    private static boolean isFinite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
