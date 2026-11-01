package dev.swarmie.adapter.transport;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import dev.swarmie.contract.BlockObservation;
import dev.swarmie.contract.BlockPosDto;
import dev.swarmie.contract.CancelAck;
import dev.swarmie.contract.CommandSpec;
import dev.swarmie.contract.CommandSubmission;
import dev.swarmie.contract.DirectionDto;
import dev.swarmie.contract.EventBatch;
import dev.swarmie.contract.InventoryState;
import dev.swarmie.contract.RobotDescriptor;
import dev.swarmie.contract.RobotId;
import dev.swarmie.contract.RobotState;
import dev.swarmie.contract.SetpointAck;
import dev.swarmie.contract.SubmitAck;
import dev.swarmie.contract.Vec3Dto;

import java.util.List;
import java.util.UUID;

/** JSON-only translation at the localhost transport boundary. */
public final class TransportCodec {
    public static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(RobotId.class,
                    (JsonSerializer<RobotId>) (id, type, context) -> new JsonPrimitive(id.toString()))
            .registerTypeAdapter(RobotId.class,
                    (JsonDeserializer<RobotId>) (json, type, context) ->
                            new RobotId(UUID.fromString(json.getAsString())))
            .create();

    private TransportCodec() {
    }

    public static CommandSubmission parseSubmit(JsonObject request) {
        JsonObject command = requireObject(request, "command");
        String type = requireString(command, "type");
        RobotId robot = new RobotId(UUID.fromString(requireString(request, "robot")));
        String correlation = requireString(request, "correlation");
        CommandSpec spec = switch (type) {
            case "TIMED_FLIGHT" -> new CommandSpec.TimedFlight(
                    parseVec(command, "velocity"), requireInt(command, "durationTicks"));
            case "STOP" -> new CommandSpec.Stop();
            case "MINE_BLOCK" -> new CommandSpec.MineBlock(parseBlockPos(command, "position"));
            case "USE_ITEM_ON_BLOCK" -> new CommandSpec.UseItemOnBlock(
                    parseBlockPos(command, "position"),
                    DirectionDto.valueOf(requireString(command, "face")),
                    parseVec(command, "hit"));
            default -> throw new IllegalArgumentException("unsupported command type: " + type);
        };
        return new CommandSubmission(robot, correlation, spec);
    }

    public static RobotId parseRobot(JsonObject request) {
        return new RobotId(UUID.fromString(requireString(request, "robot")));
    }

    public static BlockPosDto parseBlockPosition(JsonObject request) {
        return parseBlockPos(request, "position");
    }

    public static SetpointRequest parseSetpoint(JsonObject request) {
        RobotId robot = parseRobot(request);
        String channel = requireString(request, "channel");
        boolean clear = request.has("clear") && request.get("clear").getAsBoolean();
        Vec3Dto velocity = null;
        if (!clear) {
            velocity = parseVec(request, "value");
        }
        return new SetpointRequest(robot, channel, velocity, clear);
    }

    public record SetpointRequest(RobotId robotId, String channel, Vec3Dto velocity, boolean clear) {
    }

    public static String correlation(JsonObject request) {
        return requireString(request, "correlation");
    }

    public static String requestId(JsonObject request) {
        return request.has("id") && !request.get("id").isJsonNull()
                ? request.get("id").getAsString() : null;
    }

    public static String ack(String requestId, SubmitAck ack) {
        JsonObject response = base("ack", requestId);
        response.add("ack", GSON.toJsonTree(ack));
        return GSON.toJson(response);
    }

    public static String cancelAck(String requestId, CancelAck ack) {
        JsonObject response = base("cancel_ack", requestId);
        response.add("cancelAck", GSON.toJsonTree(ack));
        return GSON.toJson(response);
    }

    public static String robots(String requestId, long epoch, List<RobotDescriptor> robots) {
        JsonObject response = base("robots", requestId);
        response.addProperty("epoch", epoch);
        response.add("robots", GSON.toJsonTree(robots));
        return GSON.toJson(response);
    }

    public static String state(String requestId, RobotState state) {
        JsonObject response = base("state", requestId);
        response.add("state", GSON.toJsonTree(state));
        return GSON.toJson(response);
    }

    public static String inventory(String requestId, InventoryState inventory) {
        JsonObject response = base("inventory", requestId);
        response.add("inventory", GSON.toJsonTree(inventory));
        return GSON.toJson(response);
    }

    public static String blockObservation(String requestId, BlockObservation observation) {
        JsonObject response = base("block_observation", requestId);
        response.add("observation", GSON.toJsonTree(observation));
        return GSON.toJson(response);
    }

    public static String setpointAck(String requestId, SetpointAck ack) {
        JsonObject response = base("setpoint_ack", requestId);
        response.add("ack", GSON.toJsonTree(ack));
        return GSON.toJson(response);
    }

    public static String events(String requestId, EventBatch batch) {
        JsonObject response = base("events", requestId);
        response.add("batch", GSON.toJsonTree(batch));
        return GSON.toJson(response);
    }

    public static String pushedEvents(EventBatch batch) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "events");
        response.add("batch", GSON.toJsonTree(batch));
        return GSON.toJson(response);
    }

    public static String hello(long epoch) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "hello");
        response.addProperty("protocol", "swarmie-jsonl/1");
        response.addProperty("epoch", epoch);
        return GSON.toJson(response);
    }

    public static String error(String requestId, String code, String message) {
        JsonObject response = base("error", requestId);
        response.addProperty("code", code);
        response.addProperty("message", message == null ? "" : message);
        return GSON.toJson(response);
    }

    private static JsonObject base(String type, String requestId) {
        JsonObject response = new JsonObject();
        response.addProperty("type", type);
        if (requestId != null) {
            response.addProperty("id", requestId);
        }
        return response;
    }

    private static Vec3Dto parseVec(JsonObject parent, String name) {
        JsonObject object = requireObject(parent, name);
        return new Vec3Dto(
                requireDouble(object, "x"),
                requireDouble(object, "y"),
                requireDouble(object, "z"));
    }

    private static BlockPosDto parseBlockPos(JsonObject parent, String name) {
        JsonObject object = requireObject(parent, name);
        return new BlockPosDto(
                requireInt(object, "x"),
                requireInt(object, "y"),
                requireInt(object, "z"));
    }

    private static JsonObject requireObject(JsonObject parent, String name) {
        JsonElement value = parent.get(name);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("missing object: " + name);
        }
        return value.getAsJsonObject();
    }

    private static String requireString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || value.getAsString().isBlank()) {
            throw new IllegalArgumentException("missing string: " + name);
        }
        return value.getAsString();
    }

    private static int requireInt(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing integer: " + name);
        }
        return value.getAsInt();
    }

    private static double requireDouble(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing number: " + name);
        }
        return value.getAsDouble();
    }
}
