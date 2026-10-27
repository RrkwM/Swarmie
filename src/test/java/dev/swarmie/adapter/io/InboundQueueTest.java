package dev.swarmie.adapter.io;

import dev.swarmie.contract.AckStatus;
import dev.swarmie.contract.CommandSpec;
import dev.swarmie.contract.CommandState;
import dev.swarmie.contract.CommandSubmission;
import dev.swarmie.contract.ErrorCode;
import dev.swarmie.contract.RobotId;
import dev.swarmie.contract.SubmitAck;
import dev.swarmie.contract.Vec3Dto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class InboundQueueTest {
    @Test
    void duplicateReturnsKnownStateAndConflictIsRejected() {
        InboundQueue queue = new InboundQueue(8, 8);
        CommandSubmission submission = new CommandSubmission(
                new RobotId(UUID.randomUUID()), "corr-1",
                new CommandSpec.TimedFlight(new Vec3Dto(0.1, 0, 0), 20));

        SubmitAck first = queue.submit(submission);
        assertEquals(AckStatus.ACCEPTED, first.status());
        assertEquals(CommandState.QUEUED, first.state());

        SubmitAck duplicate = queue.submit(submission);
        assertEquals(AckStatus.DUPLICATE, duplicate.status());
        assertEquals(CommandState.QUEUED, duplicate.state());

        CommandSubmission conflict = new CommandSubmission(
                submission.robotId(), "corr-1", new CommandSpec.Stop());
        SubmitAck rejected = queue.submit(conflict);
        assertEquals(AckStatus.REJECTED, rejected.status());
        assertEquals(ErrorCode.CORRELATION_CONFLICT, rejected.error());
    }

    @Test
    void capacityAndBudgetAreBounded() {
        InboundQueue queue = new InboundQueue(2, 8);
        RobotId robot = new RobotId(UUID.randomUUID());
        assertTrue(queue.submit(new CommandSubmission(robot, "a", new CommandSpec.Stop())).status()
                == AckStatus.ACCEPTED);
        assertTrue(queue.submit(new CommandSubmission(robot, "b", new CommandSpec.Stop())).status()
                == AckStatus.ACCEPTED);
        SubmitAck full = queue.submit(new CommandSubmission(robot, "c", new CommandSpec.Stop()));
        assertEquals(ErrorCode.INBOX_FULL, full.error());

        assertEquals(1, queue.poll(1).size());
        assertEquals(1, queue.queuedCount());
        assertEquals(1, queue.poll(32).size());
        assertEquals(0, queue.queuedCount());
    }

    @Test
    void runtimeStateUpdatesDuplicateResponse() {
        InboundQueue queue = new InboundQueue(8, 8);
        RobotId robot = new RobotId(UUID.randomUUID());
        CommandSubmission submission = new CommandSubmission(robot, "lifecycle", new CommandSpec.Stop());
        queue.submit(submission);
        queue.markRuntimeAccepted("lifecycle", "42");
        queue.markTerminal("lifecycle", CommandState.SUCCEEDED, null);

        SubmitAck duplicate = queue.submit(submission);
        assertEquals(AckStatus.DUPLICATE, duplicate.status());
        assertEquals("42", duplicate.internalCommandId());
        assertEquals(CommandState.SUCCEEDED, duplicate.state());
    }

    @Test
    void removeRobotReturnsOnlyItsEntries() {
        InboundQueue queue = new InboundQueue(8, 8);
        RobotId first = new RobotId(UUID.randomUUID());
        RobotId second = new RobotId(UUID.randomUUID());
        queue.submit(new CommandSubmission(first, "a1", new CommandSpec.Stop()));
        queue.submit(new CommandSubmission(second, "b1", new CommandSpec.Stop()));
        queue.submit(new CommandSubmission(first, "a2", new CommandSpec.Stop()));

        List<InboundQueue.Entry> removed = queue.removeRobot(first);
        assertEquals(2, removed.size());
        assertEquals(1, queue.queuedCount());
        assertTrue(removed.stream().allMatch(e -> e.submission().robotId().equals(first)));
    }

    @Test
    void terminalRecordsExpireButQueuedRecordsDoNot() {
        AtomicLong clock = new AtomicLong();
        InboundQueue queue = new InboundQueue(8, 8, 100L, clock::get);
        RobotId robot = new RobotId(UUID.randomUUID());
        CommandSubmission submission = new CommandSubmission(robot, "ttl", new CommandSpec.Stop());

        queue.submit(submission);
        clock.set(1_000);
        assertEquals(AckStatus.DUPLICATE, queue.submit(submission).status());

        queue.markTerminal("ttl", CommandState.SUCCEEDED, null);
        queue.removeCorrelation("ttl");
        clock.set(1_101);
        assertEquals(AckStatus.ACCEPTED, queue.submit(submission).status());
        assertEquals(1, queue.queuedCount());
    }

    @Test
    void terminalCapacityEvictsOldestTerminalOnly() {
        AtomicLong clock = new AtomicLong();
        InboundQueue queue = new InboundQueue(8, 1, 1_000_000L, clock::get);
        RobotId robot = new RobotId(UUID.randomUUID());
        CommandSubmission first = new CommandSubmission(robot, "first", new CommandSpec.Stop());
        queue.submit(first);
        queue.markTerminal("first", CommandState.SUCCEEDED, null);

        CommandSubmission second = new CommandSubmission(robot, "second", new CommandSpec.Stop());
        assertEquals(AckStatus.ACCEPTED, queue.submit(second).status());
        assertEquals(AckStatus.ACCEPTED, queue.submit(first).status());
    }

    @Test
    void removingCorrelationReturnsQueuedEntry() {
        InboundQueue queue = new InboundQueue(8, 8);
        RobotId robot = new RobotId(UUID.randomUUID());
        queue.submit(new CommandSubmission(robot, "remove-me", new CommandSpec.Stop()));
        assertTrue(queue.removeCorrelation("remove-me").isPresent());
        assertEquals(0, queue.queuedCount());
        assertTrue(queue.removeCorrelation("missing").isEmpty());
    }

    @Test
    void concurrentDuplicatesCannotBothExecute() throws Exception {
        InboundQueue queue = new InboundQueue(8, 8);
        RobotId robot = new RobotId(UUID.randomUUID());
        CommandSubmission submission = new CommandSubmission(robot, "same", new CommandSpec.Stop());
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        List<SubmitAck> acks = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        start.await();
                        SubmitAck ack = queue.submit(submission);
                        synchronized (acks) {
                            acks.add(ack);
                        }
                        if (ack.status() == AckStatus.ACCEPTED) {
                            accepted.incrementAndGet();
                        }
                        else if (ack.status() == AckStatus.DUPLICATE) {
                            duplicates.incrementAndGet();
                        }
                    }
                    catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        finally {
            executor.shutdownNow();
        }
        assertEquals(1, accepted.get());
        assertEquals(threads - 1, duplicates.get());
        assertEquals(1, queue.queuedCount());
    }
}
