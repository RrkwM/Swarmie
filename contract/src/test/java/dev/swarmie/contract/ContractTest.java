package dev.swarmie.contract;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ContractTest {
    @Test
    void contractIsExplicitlyPreStable() {
        assertEquals(0, ContractVersion.CURRENT.major());
        assertEquals(1, ContractVersion.CURRENT.minor());
        assertTrue(ContractVersion.CURRENT.isCompatibleWith(new ContractVersion(0, 1)));
        assertFalse(ContractVersion.CURRENT.isCompatibleWith(new ContractVersion(1, 0)));
        assertEquals("0.1", ContractVersion.CURRENT.toString());
    }

    @Test
    void commandParametersAreValidated() {
        CommandSpec.TimedFlight flight = new CommandSpec.TimedFlight(
                new Vec3Dto(0.1, 0, 0), 20);
        assertEquals(CommandType.TIMED_FLIGHT, flight.type());
        assertThrows(IllegalArgumentException.class,
                () -> new CommandSpec.TimedFlight(Vec3Dto.ZERO, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new CommandSpec.TimedFlight(Vec3Dto.ZERO, ContractLimits.MAX_DURATION_TICKS + 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CommandSpec.TimedFlight(new Vec3Dto(Double.NaN, 0, 0), 1));
    }

    @Test
    void submissionAndAckCarryCorrelation() {
        RobotId robot = new RobotId(UUID.randomUUID());
        CommandSubmission submission = new CommandSubmission(
                robot, "corr-1", new CommandSpec.Stop());
        assertEquals("corr-1", submission.correlationId());

        SubmitAck accepted = SubmitAck.accepted(robot, "corr-1", CommandType.STOP, "7");
        assertEquals(AckStatus.ACCEPTED, accepted.status());
        assertEquals("7", accepted.internalCommandId());

        SubmitAck rejected = SubmitAck.rejected(robot, "corr-2", CommandType.MINE_BLOCK,
                ErrorCode.QUEUE_FULL, "full");
        assertEquals(AckStatus.REJECTED, rejected.status());
        assertEquals(ErrorCode.QUEUE_FULL, rejected.error());
        assertThrows(IllegalArgumentException.class,
                () -> new CommandSubmission(robot, "", new CommandSpec.Stop()));
    }

    @Test
    void resultAndEventShapesAreStable() {
        RobotId robot = new RobotId(UUID.randomUUID());
        CommandResult result = new CommandResult(robot, "corr-1", "7",
                CommandState.SUCCEEDED, null, "done");
        assertTrue(result.state().isTerminal());

        RobotEvent event = new RobotEvent("evt-1", 123L, 1L, 100,
                RobotEventType.COMMAND_COMPLETED, robot, "corr-1", result, null);
        assertEquals(1, event.sequence());
        assertEquals("corr-1", event.correlationId());
        assertNotNull(RobotEventType.UNKNOWN);
    }

    @Test
    void commandKindsAreLimitedToCurrentAbilities() {
        assertEquals(4, CommandType.values().length);
        assertNotNull(new CommandSpec.MineBlock(new BlockPosDto(1, 2, 3)));
        assertNotNull(new CommandSpec.UseItemOnBlock(
                new BlockPosDto(1, 2, 3), DirectionDto.UP, new Vec3Dto(1.5, 3, 2.5)));
    }

    @Test
    void batchLimitsAndDuplicateAckAreExplicit() {
        RobotId robot = new RobotId(UUID.randomUUID());
        CommandSubmission one = new CommandSubmission(robot, "b1", new CommandSpec.Stop());
        assertEquals(1, new BatchSubmission(java.util.List.of(one)).submissions().size());
        assertThrows(IllegalArgumentException.class,
                () -> new BatchSubmission(java.util.List.of()));
        java.util.List<CommandSubmission> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < ContractLimits.MAX_BATCH_SIZE + 1; i++) {
            tooMany.add(new CommandSubmission(robot, "c" + i, new CommandSpec.Stop()));
        }
        assertThrows(IllegalArgumentException.class, () -> new BatchSubmission(tooMany));

        SubmitAck duplicate = SubmitAck.duplicate(robot, "b1", CommandType.STOP,
                "7", CommandState.SUCCEEDED, "duplicate");
        assertEquals(AckStatus.DUPLICATE, duplicate.status());
        assertEquals(CommandState.SUCCEEDED, duplicate.state());
    }

    @Test
    void cancelAckHasExplicitTerminalAndUnknownStates() {
        RobotId robot = new RobotId(UUID.randomUUID());
        CancelAck terminal = CancelAck.alreadyTerminal(robot, "c", CommandState.SUCCEEDED);
        assertEquals(CancelStatus.ALREADY_TERMINAL, terminal.status());
        assertEquals(CommandState.SUCCEEDED, terminal.state());
        CancelAck unknown = CancelAck.unknown(robot, "missing");
        assertEquals(CancelStatus.UNKNOWN, unknown.status());
        assertEquals(ErrorCode.UNKNOWN_CORRELATION, unknown.error());
    }

    @Test
    void robotStateRequiresFiniteCompleteSnapshot() {
        RobotId robot = new RobotId(UUID.randomUUID());
        RobotState state = new RobotState(robot, 7L, 100L, "minecraft:overworld",
                new Vec3Dto(1, 64, -3), new Vec3Dto(0.1, 0, -0.2),
                90.0, -10.0, RobotLifecycleState.LOADED);
        assertEquals(100L, state.serverTick());
        assertEquals(RobotLifecycleState.LOADED, state.lifecycle());

        assertThrows(IllegalArgumentException.class,
                () -> new RobotState(robot, 7L, 100L, "minecraft:overworld",
                        new Vec3Dto(1, 64, -3), new Vec3Dto(0.1, 0, -0.2),
                        Double.NaN, 0.0, RobotLifecycleState.LOADED));
        assertThrows(IllegalArgumentException.class,
                () -> new RobotState(robot, 7L, 100L, " ",
                        new Vec3Dto(1, 64, -3), Vec3Dto.ZERO,
                        0.0, 0.0, RobotLifecycleState.LOADED));
    }

    @Test
    void setpointAckDistinguishesAcceptanceFromRejection() {
        RobotId robot = new RobotId(UUID.randomUUID());
        SetpointAck accepted = SetpointAck.accepted(robot, 3,
                new Vec3Dto(0.1, 0, 0), false, 7L, 100L);
        assertTrue(accepted.accepted());
        assertTrue(accepted.active());
        assertEquals(3, accepted.revision());
        assertEquals(7L, accepted.serverEpoch());
        assertNull(accepted.error());

        SetpointAck cleared = SetpointAck.cleared(robot, 4, 7L, 101L);
        assertTrue(cleared.accepted());
        assertFalse(cleared.active());
        assertNull(cleared.effectiveVelocity());

        SetpointAck rejected = SetpointAck.rejected(robot, 4, true,
                ErrorCode.MOTION_CHANNEL_BUSY, "busy", 7L, 102L);
        assertFalse(rejected.accepted());
        assertEquals(ErrorCode.MOTION_CHANNEL_BUSY, rejected.error());
        assertEquals(4, rejected.revision());
    }
}
