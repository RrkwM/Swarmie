package dev.swarmie.adapter;

import dev.swarmie.SwarmieEntity;
import dev.swarmie.contract.BatchSubmission;
import dev.swarmie.contract.BatchSubmitAck;
import dev.swarmie.contract.CommandSpec;
import dev.swarmie.contract.CommandSubmission;
import dev.swarmie.contract.EventBatch;
import dev.swarmie.contract.RobotDescriptor;
import dev.swarmie.contract.RobotId;
import dev.swarmie.contract.SubmitAck;
import dev.swarmie.adapter.event.AdapterEventBus;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.function.Consumer;

/**
 * Fabric lifecycle facade. Owns one AdapterSession per server process and does
 * not expose Minecraft objects to the contract.
 */
public final class SwarmieAdapter {
    private static volatile AdapterSession session;
    private static boolean initialized;

    private SwarmieAdapter() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        ServerLifecycleEvents.SERVER_STARTED.register(SwarmieAdapter::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(SwarmieAdapter::onServerStopping);
        ServerEntityEvents.ENTITY_LOAD.register(SwarmieAdapter::onEntityLoad);
        ServerEntityEvents.ENTITY_UNLOAD.register(SwarmieAdapter::onEntityUnload);
        ServerTickEvents.START_SERVER_TICK.register(SwarmieAdapter::onStartTick);
        ServerTickEvents.END_SERVER_TICK.register(SwarmieAdapter::onEndTick);
    }

    private static void onServerStarted(MinecraftServer server) {
        AdapterSession next = new AdapterSession(server);
        session = next;
        next.start();
    }

    private static void onServerStopping(MinecraftServer server) {
        AdapterSession current = session;
        if (current != null) {
            current.stop();
            session = null;
        }
    }

    private static void onEntityLoad(Entity entity, net.minecraft.server.level.ServerLevel level) {
        AdapterSession current = session;
        if (current != null && entity instanceof SwarmieEntity robot) {
            current.onEntityLoad(robot, level);
        }
    }

    private static void onEntityUnload(Entity entity, net.minecraft.server.level.ServerLevel level) {
        AdapterSession current = session;
        if (current != null && entity instanceof SwarmieEntity robot) {
            current.onEntityUnload(robot);
        }
    }

    private static void onStartTick(MinecraftServer server) {
        AdapterSession current = session;
        if (current != null) {
            current.onStartTick();
        }
    }

    private static void onEndTick(MinecraftServer server) {
        AdapterSession current = session;
        if (current != null) {
            current.onEndTick();
        }
    }

    public static boolean isActive() {
        return session != null;
    }

    public static void onEntityRemoved(SwarmieEntity robot, Entity.RemovalReason reason) {
        AdapterSession current = session;
        if (current != null) {
            current.onEntityRemoved(robot, reason);
        }
        else {
            robot.onAdapterSessionEnd(reason == Entity.RemovalReason.CHANGED_DIMENSION
                    ? "dimension change" : "owner removed");
        }
    }

    public static List<RobotDescriptor> snapshotRobots() {
        AdapterSession current = session;
        return current == null ? List.of() : current.snapshotRobots();
    }

    public static EventBatch readEvents(long afterSequence, int maxEvents) {
        AdapterSession current = session;
        return current == null
                ? new EventBatch(0L, afterSequence, 1L, 0L, 1L, false, List.of())
                : current.readEvents(afterSequence, maxEvents);
    }

    public static long serverEpoch() {
        AdapterSession current = session;
        return current == null ? 0L : current.serverEpoch();
    }

    public static SubmitAck submit(RobotId robotId, CommandSpec command, String correlationId) {
        return submit(new CommandSubmission(robotId, correlationId, command));
    }

    public static SubmitAck submit(CommandSubmission submission) {
        AdapterSession current = session;
        if (current == null) {
            return SubmitAck.rejected(submission.robotId(), submission.correlationId(),
                    submission.command().type(), dev.swarmie.contract.ErrorCode.ROBOT_UNAVAILABLE,
                    "server adapter is not running");
        }
        return current.submit(submission);
    }

    public static BatchSubmitAck submitBatch(BatchSubmission batch) {
        AdapterSession current = session;
        if (current == null) {
            List<SubmitAck> acks = batch.submissions().stream()
                    .map(submission -> SubmitAck.rejected(submission.robotId(), submission.correlationId(),
                            submission.command().type(), dev.swarmie.contract.ErrorCode.ROBOT_UNAVAILABLE,
                            "server adapter is not running"))
                    .toList();
            return new BatchSubmitAck(acks);
        }
        return current.submitBatch(batch);
    }

    public static dev.swarmie.contract.CancelAck cancel(RobotId robotId, String correlationId) {
        AdapterSession current = session;
        if (current == null) {
            return dev.swarmie.contract.CancelAck.rejected(robotId, correlationId,
                    dev.swarmie.contract.ErrorCode.ROBOT_UNAVAILABLE, "server adapter is not running");
        }
        return current.cancel(robotId, correlationId);
    }

    public static AdapterEventBus.Subscription subscribe(Consumer<EventBatch> listener) {
        AdapterSession current = session;
        if (current == null) {
            throw new IllegalStateException("server adapter is not running");
        }
        return current.subscribe(listener);
    }

    public static AdapterSession session() {
        return session;
    }
}
