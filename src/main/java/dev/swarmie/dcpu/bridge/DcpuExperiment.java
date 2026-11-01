package dev.swarmie.dcpu.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.swarmie.SwarmieEntity;
import dev.swarmie.adapter.SwarmieAdapter;
import dev.swarmie.contract.RobotId;
import dev.swarmie.dcpu.ExperimentGate;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Explicit development/test activation for the DCPU experiment.
 *
 * <p>Both a real Fabric development environment and the test config must agree.
 * This is not a public JSONL/Brigadier entry point and it only attaches to a
 * robot handed to it by the test harness.
 */
public final class DcpuExperiment {
    private static final Logger LOGGER = LoggerFactory.getLogger("swarmie-dcpu");
    private static final String CONFIG_FILE = "swarmie-dcpu-experiment.json";
    private static volatile boolean enabled;

    private DcpuExperiment() {
    }

    public static void initialize() {
        boolean configured = false;
        Path config = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
        if (Files.isRegularFile(config)) {
            try {
                JsonObject object = JsonParser.parseString(
                        Files.readString(config, StandardCharsets.UTF_8)).getAsJsonObject();
                configured = object.has("enabled") && object.get("enabled").getAsBoolean();
            }
            catch (IOException | RuntimeException exception) {
                LOGGER.error("DCPU experiment config rejected: {}", exception.getMessage());
                enabled = false;
                return;
            }
        }
        boolean development = FabricLoader.getInstance().isDevelopmentEnvironment();
        enabled = ExperimentGate.enabled(development, configured);
        if (enabled) {
            LOGGER.info("DCPU experiment enabled (development environment + explicit config)");
        }
        else {
            LOGGER.info("DCPU experiment disabled (development={}, configured={})",
                    development, configured);
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Called only for a robot spawned by the test harness. It does not expose a
     * UUID control path and will not attach to arbitrary entities.
     */
    public static void attachIfEnabled(SwarmieEntity robot) {
        if (!enabled || robot == null) {
            return;
        }
        RobotId id = new RobotId(robot.getUUID());
        if (!SwarmieAdapter.claimGuestMotion(id)) {
            LOGGER.warn("DCPU experiment could not claim guest motion for {}", robot.getUUID());
            return;
        }
        robot.attachDcpuExperiment(new DcpuController(robot));
    }
}
