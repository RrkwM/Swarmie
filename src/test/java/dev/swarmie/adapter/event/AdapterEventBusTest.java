package dev.swarmie.adapter.event;

import dev.swarmie.contract.EventBatch;
import dev.swarmie.contract.RobotEvent;
import dev.swarmie.contract.RobotEventType;
import dev.swarmie.contract.RobotId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AdapterEventBusTest {
    @Test
    void duplicateSubscriptionDoesNotDuplicateEvents() {
        AdapterEventBus bus = new AdapterEventBus();
        AtomicInteger calls = new AtomicInteger();
        Consumer<EventBatch> listener = batch -> calls.incrementAndGet();
        AdapterEventBus.Subscription first = bus.subscribe(listener);
        AdapterEventBus.Subscription second = bus.subscribe(listener);
        assertSame(first, second);

        bus.publish(batch(1));
        assertEquals(1, calls.get());
        first.close();
        bus.publish(batch(2));
        assertEquals(1, calls.get());
    }

    @Test
    void listenerFailureIsIsolated() {
        AdapterEventBus bus = new AdapterEventBus();
        AtomicInteger healthyCalls = new AtomicInteger();
        bus.subscribe(batch -> {
            throw new IllegalStateException("boom");
        });
        bus.subscribe(batch -> healthyCalls.incrementAndGet());
        assertDoesNotThrow(() -> bus.publish(batch(1)));
        assertEquals(1, healthyCalls.get());
    }

    private static EventBatch batch(long sequence) {
        RobotId robot = new RobotId(UUID.randomUUID());
        RobotEvent event = new RobotEvent("e" + sequence, 1L, sequence, 1L,
                RobotEventType.COMMAND_STARTED, robot, "c", null, null);
        return new EventBatch(1L, sequence - 1, sequence, sequence, sequence + 1, false, List.of(event));
    }
}
