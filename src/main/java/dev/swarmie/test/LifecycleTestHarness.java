package dev.swarmie.test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.swarmie.SwarmieEntity;
import dev.swarmie.SwarmieMod;
import dev.swarmie.adapter.SwarmieAdapter;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;

/**
 * Development-only file-driven lifecycle trigger.
 *
 * <p>It is disabled unless {@code config/swarmie-lifecycle-test.json} exists
 * with {@code enabled=true}. It never registers a command or public API; a test
 * process writes a JSON command and reads a JSON result. It acts only on a robot
 * it spawned itself and restores the player's position/flags on cleanup.
 */
public final class LifecycleTestHarness {
    private static final Logger LOGGER = LoggerFactory.getLogger("swarmie-lifecycle-test");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "swarmie-lifecycle-test.json";
    private static final String COMMAND_FILE = "command.json";
    private static final String RESULT_FILE = "result.json";

    private static MinecraftServer server;
    private static UUID testRobotId;
    private static String lastCommand;
    private static String lastCommandId;
    private static BlockPos setupTarget;
    private static BlockState setupPrevious;
    private static ServerLevel setupLevel;

    private static boolean playerSaved;
    private static ServerPlayer player;
    private static ServerLevel playerLevel;
    private static Vec3 playerPosition;
    private static float playerYaw;
    private static float playerPitch;
    private static boolean playerNoGravity;
    private static boolean playerInvulnerable;
    private static GameType playerGameMode;

    private LifecycleTestHarness() {
    }

    public static void initialize() {
        Path config = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
        if (!Files.isRegularFile(config)) {
            return;
        }
        try {
            JsonObject object = JsonParser.parseString(Files.readString(config, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (!object.has("enabled") || !object.get("enabled").getAsBoolean()) {
                return;
            }
        }
        catch (RuntimeException | IOException exception) {
            LOGGER.error("Lifecycle test harness config rejected: {}", exception.getMessage());
            return;
        }

        ServerLifecycleEvents.SERVER_STARTED.register(started -> server = started);
        ServerTickEvents.END_SERVER_TICK.register(LifecycleTestHarness::tick);
        LOGGER.info("Lifecycle test harness enabled (development-only file trigger)");
    }

    private static Path commandPath() {
        return FabricLoader.getInstance().getGameDir().resolve("swarmie-lifecycle-test").resolve(COMMAND_FILE);
    }

    private static Path resultPath() {
        return FabricLoader.getInstance().getGameDir().resolve("swarmie-lifecycle-test").resolve(RESULT_FILE);
    }

    private static void tick(MinecraftServer current) {
        if (server == null) {
            return;
        }
        Path command = commandPath();
        if (!Files.isRegularFile(command)) {
            return;
        }
        try {
            String text = Files.readString(command, StandardCharsets.UTF_8);
            JsonObject request = JsonParser.parseString(text).getAsJsonObject();
            String id = request.has("id") ? request.get("id").getAsString() : "";
            if (!id.isEmpty() && id.equals(lastCommandId)) {
                return;
            }
            if (text.equals(lastCommand)) {
                return;
            }
            lastCommand = text;
            lastCommandId = id;
            JsonObject result;
            try {
                result = handle(request);
            }
            catch (RuntimeException exception) {
                result = new JsonObject();
                result.addProperty("ok", false);
                result.addProperty("error", exception.getClass().getSimpleName() + ": " + exception.getMessage());
            }
            result.addProperty("id", id);
            result.addProperty("serverEpoch", SwarmieAdapter.serverEpoch());
            result.addProperty("serverTick", server.getTickCount());
            writeResult(result);
            LOGGER.info("Lifecycle test command {} -> {}", id, result);
        }
        catch (IOException | RuntimeException exception) {
            LOGGER.error("Lifecycle test harness failed to process command", exception);
        }
    }

    private static JsonObject handle(JsonObject request) {
        String action = request.has("action") ? request.get("action").getAsString() : "";
        return switch (action) {
            case "spawn" -> spawn();
            case "unload" -> unload();
            case "reload" -> reload();
            case "remove" -> remove();
            case "dimension" -> dimension(request);
            case "setup_farm" -> setupFarm(request);
            case "status" -> status();
            case "cleanup" -> cleanup(request);
            default -> failure("unknown action: " + action);
        };
    }

    private static JsonObject spawn() {
        ServerPlayer target = firstPlayer();
        if (target == null) {
            return failure("no player is available");
        }
        ServerLevel level = target.level();
        Vec3 position = target.position().add(2.0, 1.0, 0.0);
        SwarmieEntity robot = SwarmieMod.SWARMIE.create(level, EntitySpawnReason.COMMAND);
        if (robot == null) {
            return failure("entity type refused creation");
        }
        robot.snapTo(position.x, position.y, position.z, target.getYRot(), 0.0F);
        if (!level.addFreshEntity(robot)) {
            return failure("world refused the entity");
        }
        testRobotId = robot.getUUID();
        JsonObject result = success("spawn");
        result.addProperty("robot", testRobotId.toString());
        addRobotState(result, robot);
        return result;
    }

    private static JsonObject unload() {
        ServerPlayer target = firstPlayer();
        if (target == null) {
            return failure("no player is available");
        }
        savePlayer(target);
        ServerLevel current = target.level();
        ServerLevel destination;
        double x;
        double y;
        double z;
        if (current.dimension().equals(Level.OVERWORLD)) {
            destination = server.getLevel(Level.NETHER);
            x = 0.5;
            y = 64.0;
            z = 0.5;
        }
        else {
            destination = server.overworld();
            BlockPos spawn = destination.getRespawnData().pos();
            x = spawn.getX() + 0.5;
            y = spawn.getY() + 1.0;
            z = spawn.getZ() + 0.5;
        }
        if (destination == null) {
            return failure("destination dimension is unavailable");
        }
        target.setNoGravity(true);
        target.setPermanentlyInvulnerable(true);
        boolean moved = target.teleportTo(destination, x, y, z, Set.of(), target.getYRot(), target.getXRot(), true);
        JsonObject result = moved ? success("unload") : failure("player teleport failed");
        if (result.get("ok").getAsBoolean()) {
            result.addProperty("movedTo", destination.dimension().identifier().toString());
        }
        return result;
    }

    private static JsonObject reload() {
        if (!playerSaved || player == null || playerLevel == null) {
            return failure("no saved player position");
        }
        boolean moved = player.teleportTo(playerLevel, playerPosition.x, playerPosition.y, playerPosition.z,
                Set.of(), playerYaw, playerPitch, true);
        restorePlayerFlags();
        JsonObject result = moved ? success("reload") : failure("player return teleport failed");
        if (result.get("ok").getAsBoolean()) {
            result.addProperty("dimension", playerLevel.dimension().identifier().toString());
            result.addProperty("position", playerPosition.toString());
        }
        return result;
    }

    private static JsonObject remove() {
        SwarmieEntity robot = currentRobot();
        if (robot == null) {
            return failure("test robot is not loaded");
        }
        UUID id = robot.getUUID();
        robot.discard();
        JsonObject result = success("remove");
        result.addProperty("robot", id.toString());
        result.addProperty("method", "Entity.discard");
        return result;
    }

    private static JsonObject dimension(JsonObject request) {
        SwarmieEntity robot = currentRobot();
        if (robot == null) {
            return failure("test robot is not loaded");
        }
        String dimensionName = request.has("dimension")
                ? request.get("dimension").getAsString()
                : (robot.level().dimension().equals(Level.NETHER) ? "minecraft:overworld" : "minecraft:the_nether");
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimensionName));
        ServerLevel destination = server.getLevel(key);
        if (destination == null) {
            return failure("unknown destination dimension: " + dimensionName);
        }
        UUID id = robot.getUUID();
        BlockPos destinationPos = new BlockPos(0, 64, 0);
        destination.getChunkAt(destinationPos);
        TeleportTransition transition = new TeleportTransition(
                destination, Vec3.atCenterOf(destinationPos), Vec3.ZERO,
                robot.getYRot(), robot.getXRot(), Set.of(), TeleportTransition.PLACE_PORTAL_TICKET);
        Entity moved = robot.teleport(transition);
        JsonObject result = success("dimension");
        result.addProperty("robot", id.toString());
        result.addProperty("fromDimension", robot.level().dimension().identifier().toString());
        result.addProperty("toDimension", destination.dimension().identifier().toString());
        result.addProperty("returnedEntity", moved == null ? "null" : moved.getUUID().toString());
        result.addProperty("destinationChunkLoaded", destination.isLoaded(destinationPos));
        return result;
    }

    private static JsonObject status() {
        JsonObject result = success("status");
        SwarmieEntity robot = currentRobot();
        result.addProperty("testRobot", testRobotId == null ? "" : testRobotId.toString());
        result.addProperty("loaded", robot != null);
        if (robot != null) {
            addRobotState(result, robot);
        }
        ServerPlayer target = firstPlayer();
        if (target != null) {
            result.addProperty("playerDimension", target.level().dimension().identifier().toString());
            result.addProperty("playerPosition", target.position().toString());
        }
        return result;
    }

    /**
     * Test fixture only: place a farmland block and seed stack for the workflow.
     * It does not plant; planting must go through the public action/state path.
     */
    private static JsonObject setupFarm(JsonObject request) {
        SwarmieEntity robot = currentRobot();
        if (robot == null) {
            return failure("test robot is not loaded");
        }
        if (!(robot.level() instanceof ServerLevel level)) {
            return failure("test robot is not on a server level");
        }
        int x = request.has("x") ? request.get("x").getAsInt() : (int) Math.floor(robot.getX()) + 2;
        int y = request.has("y") ? request.get("y").getAsInt() : (int) Math.floor(robot.getY());
        int z = request.has("z") ? request.get("z").getAsInt() : (int) Math.floor(robot.getZ());
        BlockPos target = new BlockPos(x, y, z);
        if (!level.isLoaded(target)) {
            return failure("target chunk is not loaded");
        }
        if (setupTarget == null) {
            setupPrevious = level.getBlockState(target);
        }
        setupTarget = target;
        setupLevel = level;
        level.setBlockAndUpdate(target, Blocks.FARMLAND.defaultBlockState());
        level.setBlockAndUpdate(target.above(), Blocks.AIR.defaultBlockState());
        robot.inventory().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 16));
        robot.inventory().setSelectedSlot(0);
        JsonObject result = success("setup_farm");
        result.addProperty("robot", robot.getUUID().toString());
        result.addProperty("targetX", target.getX());
        result.addProperty("targetY", target.getY());
        result.addProperty("targetZ", target.getZ());
        result.addProperty("selectedSlot", robot.inventory().getSelectedSlot());
        result.addProperty("seedCount", robot.inventory().getItem(0).getCount());
        result.addProperty("tick", server.getTickCount());
        return result;
    }

    private static JsonObject cleanup(JsonObject request) {
        if (setupTarget != null && setupPrevious != null && setupLevel != null) {
            setupLevel.setBlockAndUpdate(setupTarget, setupPrevious);
            setupTarget = null;
            setupPrevious = null;
            setupLevel = null;
        }
        if (request.has("robot")) {
            try {
                UUID id = UUID.fromString(request.get("robot").getAsString());
                Entity entity = server.overworld().getEntityInAnyDimension(id);
                if (entity instanceof SwarmieEntity robot) {
                    robot.discard();
                }
            }
            catch (IllegalArgumentException exception) {
                return failure("invalid robot UUID");
            }
        }
        SwarmieEntity robot = currentRobot();
        if (robot != null) {
            robot.discard();
        }
        if (playerSaved && player != null && playerLevel != null) {
            player.teleportTo(playerLevel, playerPosition.x, playerPosition.y, playerPosition.z,
                    Set.of(), playerYaw, playerPitch, true);
            restorePlayerFlags();
        }
        playerSaved = false;
        testRobotId = null;
        return success("cleanup");
    }

    private static SwarmieEntity currentRobot() {
        if (testRobotId == null) {
            return null;
        }
        Entity entity = server.overworld().getEntityInAnyDimension(testRobotId);
        return entity instanceof SwarmieEntity robot ? robot : null;
    }

    private static void addRobotState(JsonObject result, SwarmieEntity robot) {
        result.addProperty("dimension", robot.level().dimension().identifier().toString());
        result.addProperty("x", robot.getX());
        result.addProperty("y", robot.getY());
        result.addProperty("z", robot.getZ());
        result.addProperty("velocityX", robot.getDeltaMovement().x);
        result.addProperty("velocityY", robot.getDeltaMovement().y);
        result.addProperty("velocityZ", robot.getDeltaMovement().z);
        result.addProperty("tick", server.getTickCount());
    }

    private static ServerPlayer firstPlayer() {
        return server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
    }

    private static void savePlayer(ServerPlayer target) {
        if (playerSaved) {
            return;
        }
        playerSaved = true;
        player = target;
        playerLevel = target.level();
        playerPosition = target.position();
        playerYaw = target.getYRot();
        playerPitch = target.getXRot();
        playerNoGravity = target.isNoGravity();
        playerInvulnerable = target.isPermanentlyInvulnerable();
        playerGameMode = target.gameMode();
    }

    private static void restorePlayerFlags() {
        if (!playerSaved || player == null) {
            return;
        }
        player.setNoGravity(playerNoGravity);
        player.setPermanentlyInvulnerable(playerInvulnerable);
        player.setGameMode(playerGameMode);
    }

    private static JsonObject success(String action) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("action", action);
        return result;
    }

    private static JsonObject failure(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", message);
        return result;
    }

    private static void writeResult(JsonObject result) throws IOException {
        Path path = resultPath();
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(RESULT_FILE + ".tmp");
        Files.writeString(temporary, GSON.toJson(result), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
