package dev.swarmie.adapter.registry;

import dev.swarmie.contract.CommandType;
import dev.swarmie.contract.RobotDescriptor;
import dev.swarmie.contract.RobotId;
import dev.swarmie.contract.RobotLifecycleState;
import dev.swarmie.contract.Vec3Dto;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Server-thread-owned robot registry. It stores immutable last-known metadata,
 * never a strong Entity/Level reference. External readers use the volatile
 * snapshot.
 */
public final class RobotRegistry {
    public static final int MAX_REMOVED_RECORDS = 256;

    private final Map<UUID, RobotDescriptor> records = new HashMap<>();
    private final Deque<UUID> removedOrder = new ArrayDeque<>();
    private final Set<UUID> pendingDimensionChanges = new HashSet<>();
    private volatile List<RobotDescriptor> snapshot = List.of();

    public synchronized RobotDescriptor load(
            RobotId id, String dimensionId, Vec3Dto position, Set<CommandType> commands, long tick) {
        RobotDescriptor previous = this.records.get(id.value());
        if (previous != null) {
            this.removedOrder.remove(id.value());
        }
        RobotDescriptor next = new RobotDescriptor(
                id, dimensionId, position, RobotLifecycleState.LOADED, commands, tick);
        this.records.put(id.value(), next);
        publish();
        return next;
    }

    public synchronized RobotDescriptor refresh(
            RobotId id, String dimensionId, Vec3Dto position, Set<CommandType> commands, long tick) {
        RobotDescriptor previous = this.records.get(id.value());
        if (previous == null) {
            return load(id, dimensionId, position, commands, tick);
        }
        RobotDescriptor next = new RobotDescriptor(
                id, dimensionId, position, previous.lifecycle(), commands, tick);
        this.records.put(id.value(), next);
        publish();
        return next;
    }

    public synchronized RobotDescriptor unload(
            RobotId id, String dimensionId, Vec3Dto position, Set<CommandType> commands, long tick) {
        RobotDescriptor next = new RobotDescriptor(
                id, dimensionId, position, RobotLifecycleState.UNLOADED, commands, tick);
        this.records.put(id.value(), next);
        publish();
        return next;
    }

    /** Records a dimension transition without classifying it as permanent removal. */
    public synchronized RobotDescriptor dimensionChanged(
            RobotId id, String dimensionId, Vec3Dto position, Set<CommandType> commands, long tick) {
        this.pendingDimensionChanges.add(id.value());
        RobotDescriptor next = new RobotDescriptor(
                id, dimensionId, position, RobotLifecycleState.UNLOADED, commands, tick);
        this.records.put(id.value(), next);
        publish();
        return next;
    }

    public synchronized RobotDescriptor remove(
            RobotId id, String dimensionId, Vec3Dto position, Set<CommandType> commands, long tick) {
        RobotDescriptor previous = this.records.get(id.value());
        RobotDescriptor next = new RobotDescriptor(
                id, dimensionId, position, RobotLifecycleState.REMOVED, commands, tick);
        this.records.put(id.value(), next);
        if (previous == null || previous.lifecycle() != RobotLifecycleState.REMOVED) {
            this.removedOrder.addLast(id.value());
        }
        pruneRemoved();
        publish();
        return next;
    }

    public synchronized Optional<RobotDescriptor> find(RobotId id) {
        return Optional.ofNullable(this.records.get(id.value()));
    }

    public synchronized boolean consumeDimensionChange(RobotId id) {
        return this.pendingDimensionChanges.remove(id.value());
    }

    public List<RobotDescriptor> snapshot() {
        return this.snapshot;
    }

    public synchronized void reset() {
        this.records.clear();
        this.removedOrder.clear();
        this.pendingDimensionChanges.clear();
        publish();
    }

    private void pruneRemoved() {
        while (this.removedOrder.size() > MAX_REMOVED_RECORDS) {
            UUID oldest = this.removedOrder.pollFirst();
            if (oldest == null) {
                return;
            }
            RobotDescriptor descriptor = this.records.get(oldest);
            if (descriptor != null && descriptor.lifecycle() == RobotLifecycleState.REMOVED) {
                this.records.remove(oldest);
            }
        }
    }

    private void publish() {
        this.snapshot = List.copyOf(new ArrayList<>(this.records.values()));
    }
}
