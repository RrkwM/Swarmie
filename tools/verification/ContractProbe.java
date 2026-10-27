import dev.swarmie.adapter.contract.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Executable checks for the Minecraft-independent contract. */
public final class ContractProbe {
    public static void main(String[] args) {
        testVersion();
        testGeometryAndLimits();
        testCommandRequests();
        testSnapshotsAndImmutability();
        testAcksResultsEvents();
        System.out.println("CONTRACT_PROBE_OK");
    }

    private static void testVersion() {
        check(ContractVersion.CURRENT.isCompatibleWith(new ContractVersion(1, 0)), "current 1.0 compatible");
        check(!ContractVersion.CURRENT.isCompatibleWith(new ContractVersion(2, 0)), "major mismatch rejected");
        check(!ContractVersion.CURRENT.isCompatibleWith(new ContractVersion(1, 1)), "higher minor rejected");
        check(ContractVersion.CURRENT.toString().equals("1.0"), "version text");
    }

    private static void testGeometryAndLimits() {
        Vec3Dto zero = Vec3Dto.ZERO;
        check(zero.x() == 0.0D && zero.y() == 0.0D && zero.z() == 0.0D, "zero vector");
        expectIllegal(() -> new Vec3Dto(Double.NaN, 0, 0), "NaN vector rejected");

        BlockPosDto origin = new BlockPosDto(0, 0, 0);
        check(ContractLimits.blockVolume(origin, new BlockPosDto(15, 15, 15)) == 4096L,
                "exact region volume");
        check(ContractLimits.blockVolume(origin, new BlockPosDto(16, 15, 15)) == 4352L,
                "over-limit volume measurable");
        check(ContractLimits.blockVolume(new BlockPosDto(Integer.MIN_VALUE, 0, 0),
                new BlockPosDto(Integer.MAX_VALUE, 0, 0)) == 0L, "extreme volume overflow-safe");

        BoundsDto bounds = new BoundsDto(new Vec3Dto(0, 0, 0), new Vec3Dto(1, 1, 1));
        check(bounds.volume() == 8L, "bounds volume");
        expectIllegal(() -> new BoundsDto(new Vec3Dto(1, 0, 0), new Vec3Dto(0, 1, 1)),
                "inverted bounds rejected");
    }

    private static void testCommandRequests() {
        CommandRequest.TimedFlight flight = new CommandRequest.TimedFlight(new Vec3Dto(0.1, 0, 0), 20);
        check(flight.type() == CommandType.TIMED_FLIGHT, "timed flight type");
        expectIllegal(() -> new CommandRequest.TimedFlight(Vec3Dto.ZERO, 0), "zero duration rejected");
        expectIllegal(() -> new CommandRequest.TimedFlight(Vec3Dto.ZERO, 6001), "oversized duration rejected");

        expectIllegal(() -> new CommandRequest.QueryRegion(new BlockPosDto(0, 0, 0),
                new BlockPosDto(16, 15, 15)), "oversized region request rejected");

        CommandRequest.QueryEntities query = new CommandRequest.QueryEntities(
                new BoundsDto(new Vec3Dto(0, 0, 0), new Vec3Dto(1, 1, 1)),
                Vec3Dto.ZERO, "minecraft:cow", null);
        check(query.type() == CommandType.QUERY_ENTITIES, "entity query type");

        expectIllegal(() -> new SensorConfigDto(0, 90, 10, false), "zero sensor range rejected");
        expectIllegal(() -> new SensorConfigDto(8, 181, 10, false), "oversized FOV rejected");
        check(SensorConfigDto.defaults().maxResults() == 32, "sensor defaults");

        CommandSubmission submission = new CommandSubmission(
                new RobotId(UUID.randomUUID()), "corr-1", new CommandRequest.Stop());
        check(submission.correlationId().equals("corr-1"), "submission correlation");
        expectIllegal(() -> new CommandSubmission(new RobotId(UUID.randomUUID()), "", new CommandRequest.Stop()),
                "empty correlation rejected");
    }

    private static void testSnapshotsAndImmutability() {
        Map<String, String> properties = new HashMap<>();
        properties.put("axis", "y");
        BlockSnapshotDto block = new BlockSnapshotDto(
                new BlockPosDto(1, 2, 3), "minecraft:oak_log", properties, false);
        properties.put("mutated", "true");
        check(!block.properties().containsKey("mutated"), "block properties copied");

        BlockSnapshotDto air = new BlockSnapshotDto(
                new BlockPosDto(1, 2, 3), "minecraft:air", Map.of(), true);
        BlockQueryResultDto airResult = new BlockQueryResultDto(
                QueryStatus.OK, new BlockPosDto(1, 2, 3), air, "");
        check(airResult.block().air(), "air is a successful snapshot");
        expectIllegal(() -> new BlockQueryResultDto(QueryStatus.OK, null, null, "missing"), "OK requires block");

        ItemComponentsDto components = new ItemComponentsDto(1, 10, null, Map.of());
        ItemSnapshotDto item = new ItemSnapshotDto("minecraft:iron_pickaxe", 1, components);
        check(item.count() == 1, "item count");
        expectIllegal(() -> new ItemSnapshotDto("minecraft:stone", 0, ItemComponentsDto.empty()),
                "zero count rejected");

        List<BlockSnapshotDto> source = new ArrayList<>();
        source.add(block);
        RegionQueryResultDto region = new RegionQueryResultDto(
                QueryStatus.OK, new BlockPosDto(0, 0, 0), new BlockPosDto(1, 1, 1),
                source, List.of(), true, 8, "");
        source.clear();
        check(region.blocks().size() == 1, "region list copied");
    }

    private static void testAcksResultsEvents() {
        RobotId robot = new RobotId(UUID.randomUUID());
        SubmissionAck accepted = SubmissionAck.accepted(robot, "c1", CommandType.STOP, "7");
        check(accepted.status() == AckStatus.ACCEPTED && accepted.internalCommandId().equals("7"), "accepted ack");
        SubmissionAck rejected = SubmissionAck.rejected(robot, "c2", CommandType.MINE_BLOCK,
                ErrorCode.QUEUE_FULL, "full");
        check(rejected.status() == AckStatus.REJECTED && rejected.error() == ErrorCode.QUEUE_FULL, "rejected ack");

        CommandResult result = new CommandResult(robot, "c1", "7", CommandState.SUCCEEDED,
                null, "done", CommandResultPayload.none());
        check(result.state().isTerminal(), "terminal result");
        check(result.payload() instanceof CommandResultPayload.None, "empty payload");

        AdapterEvent event = new AdapterEvent("evt-1", 1, 100, AdapterEventType.COMMAND_COMPLETED,
                robot, "c1", result, null);
        check(event.sequence() == 1 && event.commandResult() == result, "event fields");
        check(AdapterEventType.UNKNOWN != null, "unknown event enum present");
    }

    private static void expectIllegal(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message + " (no exception)");
        }
        catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
