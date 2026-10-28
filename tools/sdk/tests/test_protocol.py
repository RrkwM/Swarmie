from __future__ import annotations

import sys
import unittest
from pathlib import Path

SDK_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SDK_ROOT))
sys.path.insert(0, str(SDK_ROOT / "tests"))

from swarmie import (  # noqa: E402
    AckStatus,
    CancelStatus,
    CommandState,
    CommandType,
    EventType,
    MineBlock,
    ProtocolError,
    Stop,
    TimedFlight,
    UseItemOnBlock,
    Vec3,
    normalize_robot_id,
)
from swarmie import protocol  # noqa: E402
from mock_server import make_event  # noqa: E402

ROBOT = "11111111-1111-4111-8111-111111111111"


class CommandEncodingTest(unittest.TestCase):
    def test_timed_flight_encoding(self):
        wire = TimedFlight(Vec3(0.1, 0.0, -0.2), 20).to_wire()
        self.assertEqual(
            wire,
            {
                "type": "TIMED_FLIGHT",
                "velocity": {"x": 0.1, "y": 0.0, "z": -0.2},
                "durationTicks": 20,
            },
        )

    def test_stop_encoding(self):
        self.assertEqual(Stop().to_wire(), {"type": "STOP"})

    def test_mine_block_encoding(self):
        self.assertEqual(
            MineBlock(10, 64, -3).to_wire(),
            {"type": "MINE_BLOCK", "position": {"x": 10, "y": 64, "z": -3}},
        )

    def test_use_item_encoding(self):
        wire = UseItemOnBlock(10, 64, -3, "UP", Vec3(10.5, 65.0, -2.5)).to_wire()
        self.assertEqual(wire["type"], "USE_ITEM_ON_BLOCK")
        self.assertEqual(wire["face"], "UP")
        self.assertEqual(wire["hit"], {"x": 10.5, "y": 65.0, "z": -2.5})

    def test_invalid_commands_are_rejected(self):
        with self.assertRaises(ValueError):
            TimedFlight(Vec3(0, 0, 0), 0)
        with self.assertRaises(ValueError):
            TimedFlight(Vec3(float("nan"), 0, 0), 20)
        with self.assertRaises(ValueError):
            UseItemOnBlock(0, 0, 0, "SIDEWAYS", Vec3(0, 0, 0))
        with self.assertRaises(ValueError):
            MineBlock(0.5, 0, 0)


class AckParsingTest(unittest.TestCase):
    def test_accepted_ack_is_not_a_terminal_result(self):
        ack = protocol.parse_ack(
            {
                "robotId": ROBOT,
                "correlationId": "c1",
                "type": "STOP",
                "status": "ACCEPTED",
                "internalCommandId": "7",
                "state": "QUEUED",
                "message": "",
            }
        )
        self.assertEqual(ack.status, AckStatus.ACCEPTED)
        self.assertEqual(ack.state, CommandState.QUEUED)
        self.assertFalse(ack.state.is_terminal)
        self.assertEqual(ack.command_type, CommandType.STOP.value)

    def test_duplicate_ack_preserves_known_state(self):
        ack = protocol.parse_ack(
            {
                "robotId": ROBOT,
                "correlationId": "c1",
                "type": "STOP",
                "status": "DUPLICATE",
                "state": "SUCCEEDED",
                "message": "duplicate request",
            }
        )
        self.assertEqual(ack.status, AckStatus.DUPLICATE)
        self.assertEqual(ack.state, CommandState.SUCCEEDED)

    def test_rejected_ack_requires_error(self):
        ack = protocol.parse_ack(
            {
                "robotId": ROBOT,
                "correlationId": "c1",
                "type": "STOP",
                "status": "REJECTED",
                "error": "ROBOT_NOT_LOADED",
                "message": "robot is not loaded",
            }
        )
        self.assertEqual(ack.status, AckStatus.REJECTED)
        self.assertEqual(ack.error, "ROBOT_NOT_LOADED")

    def test_cancel_ack_unknown(self):
        ack = protocol.parse_cancel_ack(
            {
                "robotId": ROBOT,
                "correlationId": "c1",
                "status": "UNKNOWN",
                "error": "UNKNOWN_CORRELATION",
                "message": "unknown correlationId",
            }
        )
        self.assertEqual(ack.status, CancelStatus.UNKNOWN)
        self.assertEqual(ack.error, "UNKNOWN_CORRELATION")

    def test_missing_status_is_a_protocol_error(self):
        with self.assertRaises(ProtocolError):
            protocol.parse_ack({"robotId": ROBOT})


class EventParsingTest(unittest.TestCase):
    def test_batch_and_terminal_result(self):
        raw = {
            "serverEpoch": 99,
            "afterSequence": 0,
            "oldestAvailableSequence": 1,
            "latestSequence": 1,
            "nextSequence": 2,
            "missed": False,
            "events": [
                make_event(
                    99,
                    1,
                    "COMMAND_COMPLETED",
                    ROBOT,
                    correlation="c1",
                    state="SUCCEEDED",
                    message="done",
                )
            ],
        }
        batch = protocol.parse_batch(raw)
        self.assertEqual(batch.server_epoch, 99)
        self.assertFalse(batch.missed)
        event = batch.events[0]
        self.assertEqual(event.type, EventType.COMMAND_COMPLETED)
        self.assertEqual(event.command_result.state, CommandState.SUCCEEDED)
        self.assertEqual(event.command_result.correlation_id, "c1")

    def test_unknown_event_type_maps_to_unknown(self):
        batch = protocol.parse_batch(
            {
                "serverEpoch": 1,
                "afterSequence": 0,
                "oldestAvailableSequence": 1,
                "latestSequence": 1,
                "nextSequence": 2,
                "missed": False,
                "events": [
                    {
                        "eventId": "1:1",
                        "serverEpoch": 1,
                        "sequence": 1,
                        "serverTick": 5,
                        "type": "FUTURE_EVENT",
                        "robotId": ROBOT,
                    }
                ],
            }
        )
        self.assertEqual(batch.events[0].type, EventType.UNKNOWN)

    def test_missing_required_event_fields_raise(self):
        with self.assertRaises(ProtocolError):
            protocol.parse_batch(
                {
                    "serverEpoch": 1,
                    "afterSequence": 0,
                    "oldestAvailableSequence": 1,
                    "latestSequence": 1,
                    "nextSequence": 2,
                    "missed": False,
                    "events": [{"sequence": 1}],
                }
            )

    def test_robot_descriptor_parsing(self):
        info = protocol.parse_robot_info(
            {
                "robotId": ROBOT,
                "dimensionId": "minecraft:the_nether",
                "position": {"x": 1.5, "y": 60.0, "z": -2.0},
                "lifecycle": "UNLOADED",
                "supportedCommands": ["STOP"],
                "lastUpdatedTick": 12,
            }
        )
        self.assertEqual(info.robot_id, ROBOT)
        self.assertEqual(info.lifecycle.value, "UNLOADED")
        self.assertEqual(info.position.x, 1.5)
        self.assertEqual(info.supported_commands, ("STOP",))

    def test_invalid_uuid_is_rejected(self):
        with self.assertRaises(ValueError):
            normalize_robot_id("not-a-uuid")


if __name__ == "__main__":
    unittest.main()
