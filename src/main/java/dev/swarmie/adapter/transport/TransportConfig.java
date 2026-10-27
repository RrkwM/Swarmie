package dev.swarmie.adapter.transport;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Default-off localhost JSONL transport configuration. */
public record TransportConfig(
        boolean enabled,
        int port,
        int maxConnections,
        int maxLineBytes,
        int maxOutboundEvents) {

    public static final int DEFAULT_PORT = 25580;
    public static final int DEFAULT_MAX_CONNECTIONS = 4;
    public static final int DEFAULT_MAX_LINE_BYTES = 8192;
    public static final int DEFAULT_MAX_OUTBOUND = 256;

    public TransportConfig {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be in 1..65535");
        }
        if (maxConnections < 1 || maxConnections > 16) {
            throw new IllegalArgumentException("maxConnections must be in 1..16");
        }
        if (maxLineBytes < 256 || maxLineBytes > 1_048_576) {
            throw new IllegalArgumentException("maxLineBytes must be in 256..1048576");
        }
        if (maxOutboundEvents < 1 || maxOutboundEvents > 4096) {
            throw new IllegalArgumentException("maxOutboundEvents must be in 1..4096");
        }
    }

    public static Optional<TransportConfig> load(Path configDir, Gson gson) throws IOException {
        Path file = configDir.resolve("swarmie-transport.json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonObject object = gson.fromJson(Files.readString(file), JsonObject.class);
            if (object == null) {
                throw new IllegalArgumentException("empty transport config");
            }
            boolean enabled = object.has("enabled") && object.get("enabled").getAsBoolean();
            if (!enabled) {
                return Optional.empty();
            }
            int port = object.has("port") ? object.get("port").getAsInt() : DEFAULT_PORT;
            int maxConnections = object.has("maxConnections")
                    ? object.get("maxConnections").getAsInt() : DEFAULT_MAX_CONNECTIONS;
            int maxLineBytes = object.has("maxLineBytes")
                    ? object.get("maxLineBytes").getAsInt() : DEFAULT_MAX_LINE_BYTES;
            int maxOutbound = object.has("maxOutboundEvents")
                    ? object.get("maxOutboundEvents").getAsInt() : DEFAULT_MAX_OUTBOUND;
            return Optional.of(new TransportConfig(true, port, maxConnections, maxLineBytes, maxOutbound));
        }
        catch (JsonParseException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid swarmie-transport.json: " + exception.getMessage(), exception);
        }
    }
}
