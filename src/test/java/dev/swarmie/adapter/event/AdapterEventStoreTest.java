package dev.swarmie.adapter.event;

import dev.swarmie.contract.CommandState;
import dev.swarmie.contract.CommandResult;
import dev.swarmie.contract.EventBatch;
import dev.swarmie.contract.RobotEvent;
import dev.swarmie.contract.RobotEventType;
import dev.swarmie.contract.RobotId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AdapterEventStoreTest {
    @Test
    void sequencesAreContinuousAndOverflowIsDetectable() {
        RobotId robot = new RobotId(UUID.randomUUID());
        AdapterEventStore store = new AdapterEventStore(123L, 3);
        for (int i = 0; i < 5; i++) {
            RobotEvent event = store.append(RobotEventType.ROBOT_LOADED, robot, null, null, "d" + i, i);
            assertEquals(i + 1L, event.sequence());
            assertEquals(123L, event.serverEpoch());
        }
        assertTrue(store.readAfter(0, 10).missed());
        EventBatch batch = store.readAfter(0, 10);
        assertEquals(3L, batch.oldestAvailableSequence());
        assertEquals(5L, batch.latestSequence());
        assertEquals(3, batch.events().size());
        assertEquals(3L, batch.events().get(0).sequence());

        EventBatch afterOldest = store.readAfter(2, 10);
        assertFalse(afterOldest.missed());
        assertEquals(3, afterOldest.events().size());

        EventBatch upToDate = store.readAfter(5, 10);
        assertFalse(upToDate.missed());
        assertTrue(upToDate.events().isEmpty());
        assertEquals(6L, upToDate.nextSequence());
    }

    @Test
    void maxEventsReturnsPrefixAndCursorContinues() {
        RobotId robot = new RobotId(UUID.randomUUID());
        AdapterEventStore store = new AdapterEventStore(1L, 16);
        for (int i = 0; i < 4; i++) {
            store.append(RobotEventType.COMMAND_STARTED, robot, "c", null, "", i);
        }
        EventBatch first = store.readAfter(0, 2);
        assertEquals(2, first.events().size());
        assertEquals(3L, first.nextSequence());
        EventBatch second = store.readAfter(first.nextSequence() - 1, 2);
        assertEquals(2, second.events().size());
        assertEquals(4L, second.events().get(1).sequence());
    }

    @Test
    void commandResultIsStoredWithCorrelation() {
        RobotId robot = new RobotId(UUID.randomUUID());
        AdapterEventStore store = new AdapterEventStore(9L, 8);
        CommandResult result = new CommandResult(robot, "corr", "7",
                CommandState.SUCCEEDED, null, "done");
        RobotEvent event = store.append(RobotEventType.COMMAND_COMPLETED, robot, "corr", result, "done", 10);
        assertEquals("corr", event.correlationId());
        assertEquals(result, event.commandResult());
    }
}
