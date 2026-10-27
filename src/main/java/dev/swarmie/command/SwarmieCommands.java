package dev.swarmie.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.swarmie.SwarmieEntity;
import dev.swarmie.adapter.SwarmieAdapter;
import dev.swarmie.contract.CommandSpec;
import dev.swarmie.contract.CommandSubmission;
import dev.swarmie.contract.EventBatch;
import dev.swarmie.contract.RobotDescriptor;
import dev.swarmie.contract.RobotEvent;
import dev.swarmie.contract.SubmitAck;
import dev.swarmie.contract.Vec3Dto;
import dev.swarmie.query.BlockQueryResult;
import dev.swarmie.query.BlockSnapshot;
import dev.swarmie.query.DefaultWorldQuery;
import dev.swarmie.query.RegionQueryResult;
import dev.swarmie.runtime.SubmissionResult;
import dev.swarmie.runtime.SwarmieCommand;
import dev.swarmie.runtime.SwarmieRuntime;
import dev.swarmie.sensor.SensorConfig;
import dev.swarmie.sensor.SensorObservation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Server-side debug interface over the real Runtime API. No flight physics or
 * command state is duplicated here.
 */
public final class SwarmieCommands {
    private static final SimpleCommandExceptionType NOT_A_SWARMIE =
            new SimpleCommandExceptionType(Component.literal("Target is not a Swarmie"));
    private static final SimpleCommandExceptionType INVALID_VECTOR =
            new SimpleCommandExceptionType(Component.literal("Velocity components must be finite numbers"));

    private SwarmieCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("swarmie");
        root.requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                // Low-level convenience: directly set the flight target, no Runtime record.
                .then(Commands.literal("velocity")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                        .executes(SwarmieCommands::setVelocity))))))
                // Queued Runtime commands.
                .then(Commands.literal("fly")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("duration", IntegerArgumentType.integer(1))
                                                                .executes(SwarmieCommands::submitFly)))))))
                .then(Commands.literal("stop")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(SwarmieCommands::submitStop)))
                .then(Commands.literal("status")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .executes(SwarmieCommands::status)))
                .then(Commands.literal("results")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .executes(SwarmieCommands::results)))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(SwarmieCommands::cancelActive)))
                .then(Commands.literal("cancelid")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("id", LongArgumentType.longArg(1L))
                                        .executes(SwarmieCommands::cancelById))))
                .then(Commands.literal("cancelall")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(SwarmieCommands::cancelAll)))
                .then(Commands.literal("mine")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(SwarmieCommands::mine))))))
                .then(Commands.literal("use")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .then(Commands.argument("face", StringArgumentType.word())
                                                                .executes(SwarmieCommands::use)))))))
                .then(Commands.literal("inventory")
                        .then(Commands.literal("give")
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .then(Commands.argument("item", IdentifierArgument.id())
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                                        .executes(SwarmieCommands::giveItem)))))
                        .then(Commands.literal("show")
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .executes(SwarmieCommands::showInventory))))
                .then(Commands.literal("query")
                        .then(Commands.literal("block")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(SwarmieCommands::queryBlock)))))
                        .then(Commands.literal("region")
                                .then(Commands.argument("x1", IntegerArgumentType.integer())
                                        .then(Commands.argument("y1", IntegerArgumentType.integer())
                                                .then(Commands.argument("z1", IntegerArgumentType.integer())
                                                        .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                                .then(Commands.argument("y2", IntegerArgumentType.integer())
                                                                        .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                                                .executes(SwarmieCommands::queryRegion)))))))))
                .then(Commands.literal("sensor")
                        .then(Commands.literal("scan")
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .then(Commands.argument("range", DoubleArgumentType.doubleArg(
                                                        0.1D, SensorConfig.MAX_RANGE))
                                                .executes(SwarmieCommands::sensorScan)))))
                // Development diagnostics only; not part of the public Adapter contract.
                .then(Commands.literal("adapter")
                        .then(Commands.literal("status")
                                .executes(SwarmieCommands::adapterStatus))
                        .then(Commands.literal("events")
                                .then(Commands.argument("after", LongArgumentType.longArg(0L))
                                        .then(Commands.argument("max", IntegerArgumentType.integer(1, 64))
                                                .executes(SwarmieCommands::adapterEvents))))
                        .then(Commands.literal("submit")
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .then(Commands.literal("stop")
                                                .then(Commands.argument("correlation", StringArgumentType.string())
                                                        .executes(SwarmieCommands::adapterSubmitStop)))
                                        .then(Commands.literal("fly")
                                                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                                        .then(Commands.argument("duration",
                                                                                        IntegerArgumentType.integer(1))
                                                                                .then(Commands.argument("correlation",
                                                                                                StringArgumentType.string())
                                                                                        .executes(SwarmieCommands::adapterSubmitFly))))))))));
        dispatcher.register(root);
    }

    private static int setVelocity(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 velocity = readVector(context);
        int affected = 0;
        for (Entity entity : EntityArgument.getEntities(context, "targets")) {
            if (entity instanceof SwarmieEntity swarmie) {
                swarmie.setDesiredVelocity(velocity);
                affected++;
            }
        }
        if (affected == 0) {
            context.getSource().sendFailure(Component.literal("No Swarmie entities matched the selector"));
            return 0;
        }
        int count = affected;
        context.getSource().sendSuccess(
                () -> Component.literal("Set low-level velocity for " + count + " Swarmie(s)"), true);
        return affected;
    }

    private static int submitFly(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 velocity = readVector(context);
        int duration = IntegerArgumentType.getInteger(context, "duration");
        if (duration <= 0 || duration > SwarmieRuntime.MAX_DURATION_TICKS) {
            context.getSource().sendFailure(Component.literal(
                    "Duration must be between 1 and " + SwarmieRuntime.MAX_DURATION_TICKS + " ticks"));
            return 0;
        }

        int accepted = 0;
        int rejected = 0;
        String lastError = null;
        for (Entity entity : EntityArgument.getEntities(context, "targets")) {
            if (entity instanceof SwarmieEntity swarmie) {
                SubmissionResult result = swarmie.runtime().submitTimedFlight(velocity, duration);
                if (result.accepted()) {
                    accepted++;
                }
                else {
                    rejected++;
                    lastError = result.error();
                }
            }
        }

        if (accepted == 0 && rejected == 0) {
            context.getSource().sendFailure(Component.literal("No Swarmie entities matched the selector"));
            return 0;
        }
        if (accepted == 0) {
            String reason = lastError == null ? "rejected" : lastError;
            context.getSource().sendFailure(Component.literal("Timed flight rejected: " + reason));
            return 0;
        }

        int acceptedCount = accepted;
        int rejectedCount = rejected;
        context.getSource().sendSuccess(
                () -> Component.literal("Queued timed flight for " + acceptedCount + " Swarmie(s)"
                        + (rejectedCount > 0 ? ", rejected " + rejectedCount : "")), true);
        return accepted;
    }

    private static int submitStop(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        int accepted = 0;
        int rejected = 0;
        String lastError = null;
        for (Entity entity : EntityArgument.getEntities(context, "targets")) {
            if (entity instanceof SwarmieEntity swarmie) {
                SubmissionResult result = swarmie.runtime().submitStop();
                if (result.accepted()) {
                    accepted++;
                }
                else {
                    rejected++;
                    lastError = result.error();
                }
            }
        }

        if (accepted == 0 && rejected == 0) {
            context.getSource().sendFailure(Component.literal("No Swarmie entities matched the selector"));
            return 0;
        }
        if (accepted == 0) {
            String reason = lastError == null ? "rejected" : lastError;
            context.getSource().sendFailure(Component.literal("Stop rejected: " + reason));
            return 0;
        }

        int acceptedCount = accepted;
        int rejectedCount = rejected;
        context.getSource().sendSuccess(
                () -> Component.literal("Queued stop for " + acceptedCount + " Swarmie(s)"
                        + (rejectedCount > 0 ? ", rejected " + rejectedCount : "")), true);
        return accepted;
    }

    private static int status(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        SwarmieEntity swarmie = requireSwarmie(context, "target");
        SwarmieRuntime runtime = swarmie.runtime();
        String current = runtime.getCurrentCommand()
                .map(SwarmieCommands::describe)
                .orElse("none");
        int queued = runtime.getQueuedCommands().size();
        int history = runtime.getHistory().size();
        context.getSource().sendSuccess(
                () -> Component.literal("Swarmie current=" + current + "; queued=" + queued + "; retained=" + history),
                false);
        return 1;
    }

    private static int results(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        SwarmieEntity swarmie = requireSwarmie(context, "target");
        List<SwarmieCommand> history = swarmie.runtime().getHistory();
        if (history.isEmpty()) {
            context.getSource().sendFailure(Component.literal("No retained command results"));
            return 0;
        }

        int from = Math.max(0, history.size() - 5);
        StringBuilder text = new StringBuilder();
        for (int i = from; i < history.size(); i++) {
            if (text.length() > 0) {
                text.append(" | ");
            }
            text.append(describe(history.get(i)));
        }
        String message = text.toString();
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return history.size() - from;
    }

    private static int cancelActive(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        int affected = 0;
        for (Entity entity : EntityArgument.getEntities(context, "targets")) {
            if (entity instanceof SwarmieEntity swarmie && swarmie.runtime().cancelActive()) {
                affected++;
            }
        }
        if (affected == 0) {
            context.getSource().sendFailure(Component.literal("No active Swarmie command to cancel"));
            return 0;
        }
        int count = affected;
        context.getSource().sendSuccess(
                () -> Component.literal("Cancelled active command on " + count + " Swarmie(s)"), true);
        return affected;
    }

    private static int cancelById(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        long id = LongArgumentType.getLong(context, "id");
        int affected = 0;
        for (Entity entity : EntityArgument.getEntities(context, "targets")) {
            if (entity instanceof SwarmieEntity swarmie && swarmie.runtime().cancel(id)) {
                affected++;
            }
        }
        if (affected == 0) {
            context.getSource().sendFailure(Component.literal("No matching command ID for the selected Swarmie(s)"));
            return 0;
        }
        int count = affected;
        context.getSource().sendSuccess(
                () -> Component.literal("Cancelled command #" + id + " on " + count + " Swarmie(s)"), true);
        return affected;
    }

    private static int cancelAll(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        int affected = 0;
        for (Entity entity : EntityArgument.getEntities(context, "targets")) {
            if (entity instanceof SwarmieEntity swarmie) {
                affected += swarmie.runtime().cancelAll();
            }
        }
        if (affected == 0) {
            context.getSource().sendFailure(Component.literal("No commands to cancel"));
            return 0;
        }
        int count = affected;
        context.getSource().sendSuccess(
                () -> Component.literal("Cancelled " + count + " command(s)"), true);
        return affected;
    }

    private static int queryBlock(CommandContext<CommandSourceStack> context) {
        BlockPos pos = readBlockPos(context, "x", "y", "z");
        ServerLevel level = context.getSource().getLevel();
        BlockQueryResult result = new DefaultWorldQuery().queryBlock(level, pos);

        if (!result.successful()) {
            context.getSource().sendFailure(Component.literal(
                    "Block query " + result.status() + ": " + result.reason()));
            return 0;
        }
        BlockSnapshot block = result.block();
        String message = "Block " + block.pos() + ": " + block.blockId()
                + " air=" + block.air() + " properties=" + block.properties();
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int queryRegion(CommandContext<CommandSourceStack> context) {
        BlockPos from = readBlockPos(context, "x1", "y1", "z1");
        BlockPos to = readBlockPos(context, "x2", "y2", "z2");
        ServerLevel level = context.getSource().getLevel();
        RegionQueryResult result = new DefaultWorldQuery().queryRegion(level, from, to);

        if (!result.successful()) {
            context.getSource().sendFailure(Component.literal(
                    "Region query " + result.status() + ": " + result.reason()));
            return 0;
        }

        StringBuilder message = new StringBuilder("Region ").append(from).append(" -> ").append(to)
                .append(" volume=").append(result.requestedVolume())
                .append(" blocks=").append(result.blocks().size())
                .append(" unavailable=").append(result.unavailable().size())
                .append(" complete=").append(result.complete());
        for (int i = 0; i < Math.min(5, result.blocks().size()); i++) {
            BlockSnapshot block = result.blocks().get(i);
            message.append(' ').append(block.pos()).append('=').append(block.blockId());
        }
        if (result.blocks().size() > 5) {
            message.append(" ...");
        }
        String output = message.toString();
        context.getSource().sendSuccess(() -> Component.literal(output), false);
        return 1;
    }

    private static int adapterSubmitStop(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        SwarmieEntity swarmie = requireSwarmie(context, "target");
        String correlation = StringArgumentType.getString(context, "correlation");
        SubmitAck ack = SwarmieAdapter.submit(new CommandSubmission(
                new dev.swarmie.contract.RobotId(swarmie.getUUID()),
                correlation,
                new CommandSpec.Stop()));
        return reportAdapterAck(context, ack);
    }

    private static int adapterSubmitFly(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        SwarmieEntity swarmie = requireSwarmie(context, "target");
        Vec3 velocity = readVector(context);
        int duration = IntegerArgumentType.getInteger(context, "duration");
        String correlation = StringArgumentType.getString(context, "correlation");
        SubmitAck ack = SwarmieAdapter.submit(new CommandSubmission(
                new dev.swarmie.contract.RobotId(swarmie.getUUID()),
                correlation,
                new CommandSpec.TimedFlight(
                        new Vec3Dto(velocity.x, velocity.y, velocity.z), duration)));
        return reportAdapterAck(context, ack);
    }

    private static int reportAdapterAck(CommandContext<CommandSourceStack> context, SubmitAck ack) {
        String message = "Adapter " + ack.status() + " corr=" + ack.correlationId()
                + " state=" + ack.state()
                + (ack.error() == null ? "" : " error=" + ack.error())
                + (ack.message().isBlank() ? "" : " " + ack.message());
        if (ack.status() == dev.swarmie.contract.AckStatus.REJECTED) {
            context.getSource().sendFailure(Component.literal(message));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    private static int adapterStatus(CommandContext<CommandSourceStack> context) {
        var robots = SwarmieAdapter.snapshotRobots();
        long epoch = SwarmieAdapter.serverEpoch();
        long loaded = robots.stream()
                .filter(r -> r.lifecycle() == dev.swarmie.contract.RobotLifecycleState.LOADED)
                .count();
        long unloaded = robots.stream()
                .filter(r -> r.lifecycle() == dev.swarmie.contract.RobotLifecycleState.UNLOADED)
                .count();
        long removed = robots.stream()
                .filter(r -> r.lifecycle() == dev.swarmie.contract.RobotLifecycleState.REMOVED)
                .count();
        StringBuilder text = new StringBuilder("Adapter epoch=").append(epoch)
                .append(" robots=").append(robots.size())
                .append(" loaded=").append(loaded)
                .append(" unloaded=").append(unloaded)
                .append(" removed=").append(removed)
                .append(" events=").append(SwarmieAdapter.readEvents(0L, 1).latestSequence());
        for (int i = 0; i < Math.min(8, robots.size()); i++) {
            RobotDescriptor robot = robots.get(i);
            text.append(" [").append(robot.robotId()).append(' ')
                    .append(robot.lifecycle()).append(' ')
                    .append(robot.dimensionId()).append(' ')
                    .append(robot.position()).append(']');
        }
        String message = text.toString();
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return robots.size();
    }

    private static int adapterEvents(CommandContext<CommandSourceStack> context) {
        long after = LongArgumentType.getLong(context, "after");
        int max = IntegerArgumentType.getInteger(context, "max");
        EventBatch batch = SwarmieAdapter.readEvents(after, max);
        StringBuilder text = new StringBuilder("Adapter events epoch=").append(batch.serverEpoch())
                .append(" after=").append(batch.afterSequence())
                .append(" oldest=").append(batch.oldestAvailableSequence())
                .append(" latest=").append(batch.latestSequence())
                .append(" next=").append(batch.nextSequence())
                .append(" missed=").append(batch.missed());
        for (RobotEvent event : batch.events()) {
            text.append(" #").append(event.sequence())
                    .append(' ').append(event.type())
                    .append(' ').append(event.robotId());
            if (event.correlationId() != null) {
                text.append(" c=").append(event.correlationId());
            }
            if (event.commandResult() != null) {
                text.append(" state=").append(event.commandResult().state());
            }
            if (event.detail() != null && !event.detail().isBlank()) {
                text.append(" detail=").append(event.detail());
            }
        }
        String message = text.toString();
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return batch.events().size();
    }

    private static int sensorScan(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        SwarmieEntity swarmie = requireSwarmie(context, "target");
        double range = DoubleArgumentType.getDouble(context, "range");

        SensorConfig config;
        try {
            config = SensorConfig.defaults().withRange(range);
        }
        catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(Component.literal("Invalid sensor range: " + exception.getMessage()));
            return 0;
        }

        SensorObservation observation = swarmie.sense(config);
        if (!observation.successful()) {
            context.getSource().sendFailure(Component.literal(
                    "Sensor " + observation.status() + ": " + observation.reason()));
            return 0;
        }

        StringBuilder message = new StringBuilder("Sensor range=")
                .append(observation.config().range())
                .append(" fov=").append(observation.config().fovDegrees())
                .append(" blocks=").append(observation.blocks().size())
                .append(" entities=").append(observation.entities().size())
                .append(" truncated=").append(observation.truncated())
                .append(" incomplete=").append(observation.incomplete());
        for (int i = 0; i < Math.min(5, observation.blocks().size()); i++) {
            var block = observation.blocks().get(i);
            message.append(' ').append(block.block().pos()).append('=').append(block.block().blockId())
                    .append("@").append(String.format("%.1f", block.distance()));
        }
        for (int i = 0; i < Math.min(3, observation.entities().size()); i++) {
            var entity = observation.entities().get(i);
            message.append(" entity=").append(entity.entity().typeId())
                    .append("@").append(String.format("%.1f", entity.distance()));
        }
        String output = message.toString();
        context.getSource().sendSuccess(() -> Component.literal(output), false);
        return 1;
    }

    private static BlockPos readBlockPos(CommandContext<CommandSourceStack> context, String xName,
                                         String yName, String zName) {
        return new BlockPos(
                IntegerArgumentType.getInteger(context, xName),
                IntegerArgumentType.getInteger(context, yName),
                IntegerArgumentType.getInteger(context, zName));
    }

    private static int mine(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        SwarmieEntity swarmie = requireSwarmie(context, "target");
        BlockPos pos = readBlockPos(context);
        SubmissionResult result = swarmie.runtime().submitMineBlock(pos);
        return reportSubmission(context, result, "Mine");
    }

    private static int use(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        SwarmieEntity swarmie = requireSwarmie(context, "target");
        BlockPos pos = readBlockPos(context);
        String faceName = StringArgumentType.getString(context, "face");
        Direction face = Direction.byName(faceName);
        if (face == null) {
            context.getSource().sendFailure(Component.literal("Unknown block face: " + faceName));
            return 0;
        }

        Vec3 hit = Vec3.atCenterOf(pos).add(
                face.getStepX() * 0.5D,
                face.getStepY() * 0.5D,
                face.getStepZ() * 0.5D);
        SubmissionResult result = swarmie.runtime().submitUseItemOn(pos, face, hit);
        return reportSubmission(context, result, "Use");
    }

    private static int giveItem(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        SwarmieEntity swarmie = requireSwarmie(context, "target");
        Identifier id = IdentifierArgument.getId(context, "item");
        int count = IntegerArgumentType.getInteger(context, "count");

        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            context.getSource().sendFailure(Component.literal("Unknown item: " + id));
            return 0;
        }
        int max = Math.max(1, item.getDefaultMaxStackSize());
        if (count < 1 || count > max) {
            context.getSource().sendFailure(Component.literal(
                    "Count must be between 1 and " + max + " for " + id));
            return 0;
        }

        ItemStack stack = new ItemStack(item, count);
        ItemStack remainder = swarmie.inventory().insertItem(stack);
        int inserted = count - remainder.getCount();
        if (inserted == 0) {
            context.getSource().sendFailure(Component.literal("Swarmie inventory is full"));
            return 0;
        }

        int insertedCount = inserted;
        context.getSource().sendSuccess(
                () -> Component.literal("Inserted " + insertedCount + " x " + id + " into Swarmie inventory"), true);
        return inserted;
    }

    private static int showInventory(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        SwarmieEntity swarmie = requireSwarmie(context, "target");
        StringBuilder text = new StringBuilder("Swarmie inventory [selected=")
                .append(swarmie.inventory().getSelectedSlot())
                .append("]:");

        for (int i = 0; i < swarmie.inventory().getContainerSize(); i++) {
            ItemStack stack = swarmie.inventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            text.append(' ')
                    .append(i == swarmie.inventory().getSelectedSlot() ? "*" : "")
                    .append('[').append(i).append("] ")
                    .append(itemId == null ? "unknown" : itemId)
                    .append(" x").append(stack.getCount());
        }
        if (swarmie.inventory().isEmpty()) {
            text.append(" empty");
        }

        String message = text.toString();
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static BlockPos readBlockPos(CommandContext<CommandSourceStack> context) {
        return readBlockPos(context, "x", "y", "z");
    }

    private static int reportSubmission(CommandContext<CommandSourceStack> context,
                                        SubmissionResult result, String action) {
        if (!result.accepted()) {
            context.getSource().sendFailure(Component.literal(action + " rejected: " + result.error()));
            return 0;
        }
        long id = result.commandId();
        context.getSource().sendSuccess(
                () -> Component.literal("Queued " + action.toLowerCase() + " command #" + id), true);
        return 1;
    }

    private static Vec3 readVector(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        double x = DoubleArgumentType.getDouble(context, "x");
        double y = DoubleArgumentType.getDouble(context, "y");
        double z = DoubleArgumentType.getDouble(context, "z");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw INVALID_VECTOR.create();
        }
        return new Vec3(x, y, z);
    }

    private static SwarmieEntity requireSwarmie(CommandContext<CommandSourceStack> context, String argument)
            throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(context, argument);
        if (entity instanceof SwarmieEntity swarmie) {
            return swarmie;
        }
        throw NOT_A_SWARMIE.create();
    }

    private static String describe(SwarmieCommand command) {
        String result = command.getResult();
        return "#" + command.getId() + " " + command.getType() + " " + command.getState()
                + (result.isEmpty() ? "" : " (" + result + ")");
    }
}
