package dev.swarmie.adapter.transport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.swarmie.contract.ErrorCode;
import dev.swarmie.contract.RobotId;
import dev.swarmie.contract.RobotLifecycleState;
import dev.swarmie.contract.RobotState;
import dev.swarmie.contract.SetpointAck;
import dev.swarmie.contract.Vec3Dto;
import org.junit.jupiter.api.Test;

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
}
