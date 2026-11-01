from __future__ import annotations

import sys
import unittest
from pathlib import Path

SDK_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SDK_ROOT))
sys.path.insert(0, str(SDK_ROOT / "tests"))

from swarmie import (  # noqa: E402
    AckStatus,
    BlockPos,
    CancelStatus,
    CommandState,
    CommandType,
    EventType,
    InventoryState,
    MineBlock,
    ObservationStatus,
    ProtocolError,
    Stop,
    TimedFlight,
    UseItemOnBlock,
    Vec3,
    normalize_robot_id,
)
from swarmie import protocol  # noqa: E402
from mock_server import (  # noqa: E402
    make_block_observation,
    make_event,
    make_inventory,
    make_state,
)

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


class StateAndSetpointParsingTest(unittest.TestCase):
    def test_state_snapshot(self):
        state = protocol.parse_state(
            make_state(
                ROBOT,
                epoch=9,
                tick=77,
                x=1.25,
                y=64.0,
                z=-3.5,
                vx=0.1,
                vy=0.0,
                vz=-0.2,
                yaw=45.0,
                pitch=-5.0,
            )
        )
        self.assertEqual(state.robot_id, ROBOT)
        self.assertEqual(state.server_epoch, 9)
        self.assertEqual(state.server_tick, 77)
        self.assertEqual(state.position.z, -3.5)
        self.assertEqual(state.velocity.x, 0.1)
        self.assertEqual(state.yaw, 45.0)
        self.assertEqual(state.lifecycle.value, "LOADED")

    def test_state_missing_velocity_is_a_protocol_error(self):
        payload = make_state(ROBOT)
        del payload["velocity"]
        with self.assertRaises(ProtocolError):
            protocol.parse_state(payload)

    def test_setpoint_ack(self):
        ack = protocol.parse_setpoint_ack(
            {
                "robotId": ROBOT,
                "channel": "velocity",
                "revision": 3,
                "active": True,
                "accepted": True,
                "effectiveVelocity": {"x": 0.1, "y": 0.0, "z": 0.0},
                "clamped": False,
                "serverEpoch": 99,
                "serverTick": 12,
                "message": "",
            }
        )
        self.assertTrue(ack.accepted)
        self.assertTrue(ack.active)
        self.assertEqual(ack.revision, 3)
        self.assertEqual(ack.server_epoch, 99)
        self.assertEqual(ack.effective_velocity.x, 0.1)
        self.assertIsNone(ack.error)

    def test_active_setpoint_requires_effective_velocity(self):
        with self.assertRaises(ProtocolError):
            protocol.parse_setpoint_ack(
                {
                    "robotId": ROBOT,
                    "channel": "velocity",
                    "revision": 1,
                    "active": True,
                    "accepted": True,
                    "clamped": False,
                    "serverEpoch": 99,
                    "serverTick": 1,
                }
            )

    def test_inventory_parser_keeps_slot_order_and_selection(self):
        inventory = protocol.parse_inventory(
            make_inventory(
                ROBOT,
                epoch=99,
                tick=12,
                selected_slot=2,
                items={
                    0: {"itemId": "minecraft:wheat_seeds", "count": 6},
                    2: {"itemId": "minecraft:iron_hoe", "count": 1},
                },
            )
        )
        self.assertIsInstance(inventory, InventoryState)
        self.assertEqual(inventory.selected_slot, 2)
        self.assertEqual(inventory.server_epoch, 99)
        self.assertEqual(inventory.server_tick, 12)
        self.assertEqual(inventory.slots[0].item_id, "minecraft:wheat_seeds")
        self.assertEqual(inventory.slots[2].count, 1)
        self.assertTrue(inventory.slots[1].empty)

    def test_inventory_parser_rejects_unordered_or_short_slots(self):
        payload = make_inventory(ROBOT)
        payload["slots"][0]["slot"] = 1
        with self.assertRaises(ProtocolError):
            protocol.parse_inventory(payload)

        short = make_inventory(ROBOT)
        short["slots"] = short["slots"][:8]
        with self.assertRaises(ProtocolError):
            protocol.parse_inventory(short)

    def test_block_observation_distinguishes_unloaded_from_air(self):
        air = protocol.parse_block_observation(
            make_block_observation(
                ROBOT,
                {"x": 1, "y": 64, "z": -3},
                status="OK",
                block_id="minecraft:air",
                air=True,
                epoch=99,
                tick=12,
            )
        )
        self.assertEqual(air.status, ObservationStatus.OK)
        self.assertTrue(air.block.air)
        self.assertEqual(air.position, BlockPos(1, 64, -3))

        unloaded = protocol.parse_block_observation(
            make_block_observation(
                ROBOT,
                {"x": 100000, "y": 64, "z": 100000},
                status="UNLOADED",
                reason="chunk is not loaded",
                epoch=99,
                tick=13,
            )
        )
        self.assertEqual(unloaded.status, ObservationStatus.UNLOADED)
        self.assertIsNone(unloaded.block)
        self.assertEqual(unloaded.reason, "chunk is not loaded")

    def test_block_observation_parser_rejects_fabricated_air(self):
        payload = make_block_observation(ROBOT, {"x": 0, "y": 64, "z": 0}, status="OK")
        payload["block"] = None
        with self.assertRaises(ProtocolError):
            protocol.parse_block_observation(payload)


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
