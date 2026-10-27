package dev.swarmie.adapter.event;

import dev.swarmie.contract.CommandResult;
import dev.swarmie.contract.EventBatch;
import dev.swarmie.contract.RobotEvent;
import dev.swarmie.contract.RobotEventType;
import dev.swarmie.contract.RobotId;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded ring event store. Writes are server-thread only; reads are
 * synchronized so external consumers can call readAfter safely.
 */
public final class AdapterEventStore {
    public static final int DEFAULT_CAPACITY = 1024;

    private final long serverEpoch;
    private final RobotEvent[] ring;
    private int head;
    private int size;
    private long nextSequence = 1L;
    private long oldestSequence = 1L;

    public AdapterEventStore(long serverEpoch, int capacity) {
        if (serverEpoch < 0 || capacity < 1) {
            throw new IllegalArgumentException("serverEpoch/capacity invalid");
        }
        this.serverEpoch = serverEpoch;
        this.ring = new RobotEvent[capacity];
    }

    public long serverEpoch() {
        return this.serverEpoch;
    }

    public synchronized RobotEvent append(
            RobotEventType type,
            RobotId robotId,
            String correlationId,
            CommandResult commandResult,
            String detail,
            long serverTick) {
        long sequence = this.nextSequence++;
        RobotEvent event = new RobotEvent(
                this.serverEpoch + ":" + sequence,
                this.serverEpoch,
                sequence,
                serverTick,
                type,
                robotId,
                correlationId,
                commandResult,
                detail);
        if (this.size < this.ring.length) {
            this.ring[(this.head + this.size) % this.ring.length] = event;
            this.size++;
        }
        else {
            this.ring[this.head] = event;
            this.head = (this.head + 1) % this.ring.length;
            this.oldestSequence++;
        }
        return event;
    }

    public synchronized EventBatch readAfter(long afterSequence, int maxEvents) {
        long latest = this.nextSequence - 1L;
        long oldest = this.size == 0 ? this.nextSequence : this.oldestSequence;
        if (maxEvents < 1 || afterSequence >= latest) {
            boolean future = afterSequence > latest;
            return new EventBatch(this.serverEpoch, afterSequence, oldest, latest,
                    future ? afterSequence : this.nextSequence, future, List.of());
        }

        boolean missed = afterSequence < oldest - 1L;
        long start = Math.max(afterSequence + 1L, oldest);
        int count = (int) Math.min((long) maxEvents, latest - start + 1L);
        List<RobotEvent> events = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long sequence = start + i;
            int index = (int) ((sequence - oldest + this.head) % this.ring.length);
            RobotEvent event = this.ring[index];
            if (event == null || event.sequence() != sequence) {
                throw new IllegalStateException("event ring inconsistency at sequence " + sequence);
            }
            events.add(event);
        }
        long next = events.isEmpty() ? this.nextSequence : events.get(events.size() - 1).sequence() + 1L;
        return new EventBatch(this.serverEpoch, afterSequence, oldest, latest, next, missed, events);
    }

    public synchronized int size() {
        return this.size;
    }
}
