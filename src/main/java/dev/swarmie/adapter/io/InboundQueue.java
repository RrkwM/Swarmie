package dev.swarmie.adapter.io;

import dev.swarmie.contract.AckStatus;
import dev.swarmie.contract.CommandSpec;
import dev.swarmie.contract.CommandState;
import dev.swarmie.contract.CommandSubmission;
import dev.swarmie.contract.CommandType;
import dev.swarmie.contract.ErrorCode;
import dev.swarmie.contract.RobotId;
import dev.swarmie.contract.SubmitAck;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Thread-safe bounded inbox and correlation registry. Submission performs only
 * pure validation, idempotency lookup, and enqueue; no Minecraft work occurs here.
 */
public final class InboundQueue {
    public static final int DEFAULT_CAPACITY = 256;
    public static final int MAX_IDEMPOTENCY_RECORDS = 1024;

    private final int capacity;
    private final int idempotencyCapacity;
    private final long ttlNanos;
    private final LongSupplier nanoClock;
    private final Deque<Entry> queue = new ArrayDeque<>();
    private final Map<String, Record> records = new LinkedHashMap<>(16, 0.75F, true);

    public InboundQueue() {
        this(DEFAULT_CAPACITY, MAX_IDEMPOTENCY_RECORDS,
                TimeUnit.SECONDS.toNanos(dev.swarmie.contract.ContractLimits.IDEMPOTENCY_TTL_SECONDS),
                System::nanoTime);
    }

    public InboundQueue(int capacity, int idempotencyCapacity) {
        this(capacity, idempotencyCapacity,
                TimeUnit.SECONDS.toNanos(dev.swarmie.contract.ContractLimits.IDEMPOTENCY_TTL_SECONDS),
                System::nanoTime);
    }

    public InboundQueue(int capacity, int idempotencyCapacity, long ttlNanos, LongSupplier nanoClock) {
        if (capacity < 1 || idempotencyCapacity < 1 || ttlNanos < 1 || nanoClock == null) {
            throw new IllegalArgumentException("capacity/ttl must be positive");
        }
        this.capacity = capacity;
        this.idempotencyCapacity = idempotencyCapacity;
        this.ttlNanos = ttlNanos;
        this.nanoClock = nanoClock;
    }

    public synchronized SubmitAck submit(CommandSubmission submission) {
        pruneExpired();
        String correlationId = submission.correlationId();
        Record existing = this.records.get(correlationId);
        if (existing != null) {
            if (existing.submission.robotId().equals(submission.robotId())
                    && existing.submission.command().equals(submission.command())) {
                return SubmitAck.duplicate(
                        submission.robotId(),
                        correlationId,
                        submission.command().type(),
                        existing.internalCommandId,
                        existing.state,
                        "duplicate correlationId; existing command state returned");
            }
            return SubmitAck.rejected(
                    submission.robotId(),
                    correlationId,
                    submission.command().type(),
                    ErrorCode.CORRELATION_CONFLICT,
                    "correlationId already belongs to a different robot or command");
        }
        if (this.queue.size() >= this.capacity) {
            return SubmitAck.rejected(
                    submission.robotId(),
                    correlationId,
                    submission.command().type(),
                    ErrorCode.INBOX_FULL,
                    "inbound queue is full (" + this.capacity + ")");
        }

        Record record = new Record(submission);
        this.records.put(correlationId, record);
        this.queue.addLast(new Entry(submission, record));
        pruneCapacity();
        return SubmitAck.accepted(submission.robotId(), correlationId, submission.command().type(), null);
    }

    public synchronized List<Entry> poll(int budget) {
        int count = Math.min(Math.max(0, budget), this.queue.size());
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(this.queue.pollFirst());
        }
        return entries;
    }

    public synchronized List<Entry> removeRobot(RobotId robotId) {
        List<Entry> removed = new ArrayList<>();
        var iterator = this.queue.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.submission.robotId().equals(robotId)) {
                iterator.remove();
                removed.add(entry);
            }
        }
        return removed;
    }

    public synchronized Optional<Entry> removeCorrelation(String correlationId) {
        Iterator<Entry> iterator = this.queue.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.submission.correlationId().equals(correlationId)) {
                iterator.remove();
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<Record> findRecord(String correlationId) {
        return Optional.ofNullable(this.records.get(correlationId));
    }

    public synchronized List<Entry> drain() {
        List<Entry> entries = new ArrayList<>(this.queue);
        this.queue.clear();
        return entries;
    }

    public synchronized void markRuntimeAccepted(String correlationId, String internalCommandId) {
        Record record = this.records.get(correlationId);
        if (record != null) {
            record.internalCommandId = internalCommandId;
            record.state = CommandState.QUEUED;
        }
    }

    public synchronized void markTerminal(String correlationId, CommandState state, ErrorCode error) {
        Record record = this.records.get(correlationId);
        if (record != null) {
            record.state = state;
            record.error = error;
            record.terminalAtNanos = this.nanoClock.getAsLong();
        }
    }

    public synchronized int queuedCount() {
        return this.queue.size();
    }

    public synchronized int recordCount() {
        return this.records.size();
    }

    public synchronized boolean containsCorrelation(String correlationId) {
        return this.records.containsKey(correlationId);
    }

    private void pruneExpired() {
        long now = this.nanoClock.getAsLong();
        Iterator<Map.Entry<String, Record>> iterator = this.records.entrySet().iterator();
        while (iterator.hasNext()) {
            Record record = iterator.next().getValue();
            if (record.state.isTerminal() && record.terminalAtNanos > 0
                    && now - record.terminalAtNanos >= this.ttlNanos) {
                iterator.remove();
            }
        }
    }

    private void pruneCapacity() {
        while (this.records.size() > this.idempotencyCapacity) {
            Iterator<Map.Entry<String, Record>> iterator = this.records.entrySet().iterator();
            boolean removed = false;
            while (iterator.hasNext()) {
                Record record = iterator.next().getValue();
                if (record.state.isTerminal()) {
                    iterator.remove();
                    removed = true;
                    break;
                }
            }
            if (!removed) {
                return;
            }
        }
    }

    public record Entry(CommandSubmission submission, Record record) {
    }

    public static final class Record {
        private final CommandSubmission submission;
        private String internalCommandId;
        private CommandState state = CommandState.QUEUED;
        private ErrorCode error;
        private long terminalAtNanos;

        private Record(CommandSubmission submission) {
            this.submission = submission;
        }

        public CommandSubmission submission() {
            return this.submission;
        }

        public String internalCommandId() {
            return this.internalCommandId;
        }

        public CommandState state() {
            return this.state;
        }

        public ErrorCode error() {
            return this.error;
        }
    }
}
