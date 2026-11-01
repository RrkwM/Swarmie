package dev.swarmie.adapter.transport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.swarmie.contract.BlockObservation;
import dev.swarmie.contract.BlockPosDto;
import dev.swarmie.contract.BlockSnapshotDto;
import dev.swarmie.contract.ErrorCode;
import dev.swarmie.contract.InventorySlot;
import dev.swarmie.contract.InventoryState;
import dev.swarmie.contract.ObservationStatus;
import dev.swarmie.contract.RobotId;
import dev.swarmie.contract.RobotLifecycleState;
import dev.swarmie.contract.RobotState;
import dev.swarmie.contract.SetpointAck;
import dev.swarmie.contract.Vec3Dto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransportCodecTest {
    @Test
    void stateResponseCarriesServerAuthoritativeFields() {
        RobotId robot = new RobotId(UUID.randomUUID());
        RobotState state = new RobotState(
                robot, 42L, 100L, "minecraft:overworld",
                new Vec3Dto(1, 64, -3), new Vec3Dto(0.1, 0, -0.2),
                90.0, -10.0, RobotLifecycleState.LOADED);

        JsonObject response = JsonParser.parseString(TransportCodec.state("7", state)).getAsJsonObject();
        assertEquals("state", response.get("type").getAsString());
        assertEquals("7", response.get("id").getAsString());
        JsonObject payload = response.getAsJsonObject("state");
        assertEquals(robot.toString(), payload.get("robotId").getAsString());
        assertEquals("minecraft:overworld", payload.get("dimensionId").getAsString());
        assertEquals(100L, payload.get("serverTick").getAsLong());
        assertEquals("LOADED", payload.get("lifecycle").getAsString());
        assertEquals(0.1, payload.getAsJsonObject("velocity").get("x").getAsDouble(), 1.0E-9);
    }

    @Test
    void setpointAckSerializesAcceptanceAndRejection() {
        RobotId robot = new RobotId(UUID.randomUUID());
        JsonObject accepted = JsonParser.parseString(TransportCodec.setpointAck(
                "8", SetpointAck.accepted(robot, 3, new Vec3Dto(0.1, 0, 0), false, 42L, 9)
        )).getAsJsonObject();
        assertEquals("setpoint_ack", accepted.get("type").getAsString());
        JsonObject ack = accepted.getAsJsonObject("ack");
        assertTrue(ack.get("accepted").getAsBoolean());
        assertTrue(ack.get("active").getAsBoolean());
        assertEquals(3L, ack.get("revision").getAsLong());
        assertEquals(42L, ack.get("serverEpoch").getAsLong());

        JsonObject rejected = JsonParser.parseString(TransportCodec.setpointAck(
                "9", SetpointAck.rejected(robot, 3, true, ErrorCode.MOTION_CHANNEL_BUSY, "busy", 42L, 9)
        )).getAsJsonObject();
        assertFalse(rejected.getAsJsonObject("ack").get("accepted").getAsBoolean());
        assertEquals("MOTION_CHANNEL_BUSY", rejected.getAsJsonObject("ack").get("error").getAsString());
    }

    @Test
    void setpointRequestParsesValueAndClear() {
        RobotId robot = new RobotId(UUID.randomUUID());
        JsonObject set = JsonParser.parseString("""
                {"robot":"%s","channel":"velocity","value":{"x":0.1,"y":0,"z":0}}
                """.formatted(robot)).getAsJsonObject();
        TransportCodec.SetpointRequest parsed = TransportCodec.parseSetpoint(set);
        assertEquals(robot, parsed.robotId());
        assertEquals("velocity", parsed.channel());
        assertFalse(parsed.clear());
        assertEquals(0.1, parsed.velocity().x(), 1.0E-9);

        JsonObject clear = JsonParser.parseString("""
                {"robot":"%s","channel":"velocity","clear":true}
                """.formatted(robot)).getAsJsonObject();
        TransportCodec.SetpointRequest cleared = TransportCodec.parseSetpoint(clear);
        assertTrue(cleared.clear());
        assertNull(cleared.velocity());
    }

    @Test
    void inventoryResponseKeepsStableSlotOrderAndFreshness() {
        RobotId robot = new RobotId(UUID.randomUUID());
        List<InventorySlot> slots = new ArrayList<>();
        for (int i = 0; i < InventoryState.SLOT_COUNT; i++) {
            slots.add(i == 1
                    ? new InventorySlot(i, "minecraft:wheat_seeds", 4)
                    : new InventorySlot(i, null, 0));
        }
        InventoryState inventory = new InventoryState(robot, 42L, 17L, 1, slots);

        JsonObject response = JsonParser.parseString(
                TransportCodec.inventory("10", inventory)).getAsJsonObject();
        assertEquals("inventory", response.get("type").getAsString());
        assertEquals("10", response.get("id").getAsString());
        JsonObject payload = response.getAsJsonObject("inventory");
        assertEquals(42L, payload.get("serverEpoch").getAsLong());
        assertEquals(17L, payload.get("serverTick").getAsLong());
        assertEquals(9, payload.getAsJsonArray("slots").size());
        assertEquals(1, payload.get("selectedSlot").getAsInt());
        assertEquals("minecraft:wheat_seeds",
                payload.getAsJsonArray("slots").get(1).getAsJsonObject().get("itemId").getAsString());
        assertTrue(!payload.getAsJsonArray("slots").get(0).getAsJsonObject().has("itemId")
                || payload.getAsJsonArray("slots").get(0).getAsJsonObject().get("itemId").isJsonNull());
    }

    @Test
    void blockObservationCarriesStatusAndNoFabricatedBlock() {
        RobotId robot = new RobotId(UUID.randomUUID());
        BlockPosDto pos = new BlockPosDto(10, 64, -3);
        BlockObservation observed = new BlockObservation(
                robot, pos, ObservationStatus.OK,
                new BlockSnapshotDto(pos, "minecraft:farmland", Map.of("moisture", "7"), false),
                "", 42L, 17L);
        JsonObject ok = JsonParser.parseString(
                TransportCodec.blockObservation("11", observed)).getAsJsonObject();
        assertEquals("block_observation", ok.get("type").getAsString());
        JsonObject okPayload = ok.getAsJsonObject("observation");
        assertEquals("minecraft:farmland",
                okPayload.getAsJsonObject("block").get("blockId").getAsString());
        assertEquals("7",
                okPayload.getAsJsonObject("block").getAsJsonObject("properties").get("moisture").getAsString());

        BlockObservation unloaded = new BlockObservation(
                robot, pos, ObservationStatus.UNLOADED, null, "chunk is not loaded", 42L, 17L);
        JsonObject unknown = JsonParser.parseString(
                TransportCodec.blockObservation("12", unloaded)).getAsJsonObject();
        JsonObject payload = unknown.getAsJsonObject("observation");
        assertEquals("UNLOADED", payload.get("status").getAsString());
        assertFalse(payload.has("block"));
        assertEquals("chunk is not loaded", payload.get("reason").getAsString());
    }

    @Test
    void observeBlockRequestParsesIntegerCoordinates() {
        JsonObject request = JsonParser.parseString("""
                {"op":"observe_block","robot":"00000000-0000-4000-8000-000000000000",
                 "position":{"x":1,"y":64,"z":-3}}
                """).getAsJsonObject();
        BlockPosDto pos = TransportCodec.parseBlockPosition(request);
        assertEquals(1, pos.x());
        assertEquals(64, pos.y());
        assertEquals(-3, pos.z());
        assertThrows(IllegalArgumentException.class,
                () -> TransportCodec.parseBlockPosition(new JsonObject()));
    }
}
