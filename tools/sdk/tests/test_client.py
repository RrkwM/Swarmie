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
    CancelUnknown,
    CommandRejected,
    CommandState,
    ConnectionClosed,
    EventGapError,
    InventoryState,
    ObservationStatus,
    ProtocolError,
    RobotNotFound,
    RobotNotLoaded,
    ServerError,
    SessionChangedError,
    SetpointRejected,
    Stop,
    SwarmieClient,
    Vec3,
    WaitTimeout,
)
from mock_server import (  # noqa: E402
    MockSwarmieServer,
    make_block_observation,
    make_event,
    make_inventory,
    make_robot,
    make_state,
)

ROBOT_A = "11111111-1111-4111-8111-111111111111"
ROBOT_B = "22222222-2222-4222-8222-222222222222"


class ClientTestCase(unittest.TestCase):
    def setUp(self):
        self.server = MockSwarmieServer(
            epoch=4242,
            robots=[
                make_robot(ROBOT_A),
                make_robot(ROBOT_B, dimension="minecraft:the_nether"),
            ],
        ).start()
        self.client = SwarmieClient(
            port=self.server.port, connect_timeout=2.0, request_timeout=1.0
        )

    def tearDown(self):
        self.client.close()
        self.server.stop()

    def event(self, sequence, event_type, robot, correlation, state, **kwargs):
        return make_event(
            self.server.epoch,
            sequence,
            event_type,
            robot,
            correlation=correlation,
            state=state,
            **kwargs,
        )

    # ------------------------------------------------------------------
    # Session, discovery and selection
    # ------------------------------------------------------------------
    def test_hello_sets_protocol_and_epoch(self):
        self.assertEqual(self.client.protocol, "swarmie-jsonl/1")
        self.assertEqual(self.client.epoch, 4242)
        self.assertTrue(self.client.connected)

    def test_exact_robot_selection_never_defaults_to_first(self):
        robot_b = self.client.robot(ROBOT_B)
        self.assertEqual(robot_b.robot_id, ROBOT_B)
        self.assertEqual(robot_b.lifecycle.value, "LOADED")
        with self.assertRaises(RobotNotFound):
            self.client.robot("00000000-0000-4000-8000-000000000000")

    def test_robot_info_is_structured(self):
        robots = self.client.robots()
        self.assertEqual(len(robots), 2)
        by_id = {robot.robot_id: robot for robot in robots}
        self.assertEqual(by_id[ROBOT_B].dimension, "minecraft:the_nether")
        self.assertIn("STOP", by_id[ROBOT_A].supported_commands)

    # ------------------------------------------------------------------
    # Live self-state and velocity setpoints
    # ------------------------------------------------------------------
    def test_state_reads_exact_uuid_snapshot(self):
        self.server.state_handler = lambda request: make_state(
            ROBOT_A,
            epoch=4242,
            tick=55,
            dimension="minecraft:overworld",
            x=1.5,
            y=64.0,
            z=-2.5,
            vx=0.1,
            vy=-0.2,
            yaw=90.0,
            pitch=12.0,
        )
        state = self.client.robot(ROBOT_A).state()
        self.assertEqual(state.robot_id, ROBOT_A)
        self.assertEqual(state.server_tick, 55)
        self.assertEqual(state.position.x, 1.5)
        self.assertEqual(state.velocity.y, -0.2)
        self.assertEqual(state.yaw, 90.0)
        self.assertEqual(state.lifecycle.value, "LOADED")
        request = [r for r in self.server.requests if r.get("op") == "state"][-1]
        self.assertEqual(request["robot"], ROBOT_A)

    def test_state_does_not_replace_registry_metadata(self):
        robot = self.client.robot(ROBOT_A)
        before = robot.info.last_updated_tick
        self.server.state_handler = lambda request: make_state(ROBOT_A, tick=500)
        state = robot.state()
        self.assertEqual(state.server_tick, 500)
        self.assertEqual(robot.info.last_updated_tick, before)

    def test_state_unknown_robot_is_not_empty_state(self):
        with self.assertRaises(RobotNotFound):
            self.client.state("00000000-0000-4000-8000-000000000000")

    def test_state_unloaded_robot_is_explicit(self):
        self.server.error_for_op = {
            "op": "state",
            "code": "ROBOT_NOT_LOADED",
            "message": "robot is not loaded",
        }
        with self.assertRaises(RobotNotLoaded) as caught:
            self.client.robot(ROBOT_A).state()
        self.assertEqual(caught.exception.code, "ROBOT_NOT_LOADED")

    def test_set_velocity_and_clear_use_setpoint_operation(self):
        robot = self.client.robot(ROBOT_A)
        ack = robot.set_velocity(Vec3(0.1, 0.0, -0.2))
        self.assertTrue(ack.accepted)
        self.assertTrue(ack.active)
        self.assertEqual(ack.channel, "velocity")
        self.assertEqual(ack.effective_velocity.z, -0.2)
        request = [r for r in self.server.requests if r.get("op") == "setpoint"][-1]
        self.assertEqual(request["value"], {"x": 0.1, "y": 0.0, "z": -0.2})

        cleared = robot.clear_velocity()
        self.assertTrue(cleared.accepted)
        self.assertFalse(cleared.active)
        self.assertIsNone(cleared.effective_velocity)
        request = [r for r in self.server.requests if r.get("op") == "setpoint"][-1]
        self.assertTrue(request["clear"])

        with self.assertRaises(ValueError):
            robot.set_velocity(Vec3(float("nan"), 0.0, 0.0))

    def test_rejected_setpoint_is_not_reported_as_accepted(self):
        self.server.setpoint_handler = lambda request: {
            "robotId": request["robot"],
            "channel": "velocity",
            "revision": 4,
            "active": False,
            "accepted": False,
            "clamped": False,
            "serverEpoch": 4242,
            "serverTick": 9,
            "error": "MOTION_CHANNEL_BUSY",
            "message": "legacy motion owns the channel",
        }
        with self.assertRaises(SetpointRejected) as caught:
            self.client.robot(ROBOT_A).set_velocity(Vec3(0.1, 0.0, 0.0))
        self.assertEqual(caught.exception.ack.error, "MOTION_CHANNEL_BUSY")
        self.assertFalse(caught.exception.ack.accepted)

    def test_setpoint_unloaded_robot_is_explicit(self):
        self.server.setpoint_handler = lambda request: {
            "robotId": request["robot"],
            "channel": "velocity",
            "revision": 0,
            "active": False,
            "accepted": False,
            "clamped": False,
            "serverEpoch": 4242,
            "serverTick": 9,
            "error": "ROBOT_NOT_LOADED",
            "message": "robot is not loaded",
        }
        with self.assertRaises(RobotNotLoaded) as caught:
            self.client.robot(ROBOT_A).set_velocity(Vec3(0.1, 0.0, 0.0))
        self.assertEqual(caught.exception.code, "ROBOT_NOT_LOADED")

    # ------------------------------------------------------------------
    # Inventory and explicit block observation
    # ------------------------------------------------------------------
    def test_inventory_reads_stable_slots_selection_and_freshness(self):
        self.server.inventory_handler = lambda request: make_inventory(
            ROBOT_A,
            epoch=4242,
            tick=77,
            selected_slot=1,
            items={
                0: {"itemId": "minecraft:wheat_seeds", "count": 4},
                1: {"itemId": "minecraft:iron_hoe", "count": 1},
            },
        )

        inventory = self.client.robot(ROBOT_A).inventory()

        self.assertIsInstance(inventory, InventoryState)
        self.assertEqual(inventory.robot_id, ROBOT_A)
        self.assertEqual(inventory.server_epoch, 4242)
        self.assertEqual(inventory.server_tick, 77)
        self.assertEqual(inventory.selected_slot, 1)
        self.assertEqual(len(inventory.slots), 9)
        self.assertEqual(inventory.slots[0].item_id, "minecraft:wheat_seeds")
        self.assertEqual(inventory.slots[0].count, 4)
        self.assertEqual(inventory.slots[1].item_id, "minecraft:iron_hoe")
        self.assertTrue(inventory.slots[2].empty)
        self.assertEqual([slot.slot for slot in inventory.slots], list(range(9)))
        request = [r for r in self.server.requests if r.get("op") == "inventory"][-1]
        self.assertEqual(request["robot"], ROBOT_A)

    def test_inventory_unloaded_robot_is_explicit(self):
        self.server.error_for_op = {
            "op": "inventory",
            "code": "ROBOT_NOT_LOADED",
            "message": "robot is not loaded",
        }
        with self.assertRaises(RobotNotLoaded) as caught:
            self.client.robot(ROBOT_A).inventory()
        self.assertEqual(caught.exception.code, "ROBOT_NOT_LOADED")

    def test_observe_block_returns_explicit_snapshot(self):
        self.server.block_handler = lambda request: make_block_observation(
            ROBOT_A,
            request["position"],
            status="OK",
            block_id="minecraft:farmland",
            properties={"moisture": "7"},
            epoch=4242,
            tick=88,
        )

        observation = self.client.robot(ROBOT_A).observe_block(BlockPos(10, 64, -3))

        self.assertEqual(observation.robot_id, ROBOT_A)
        self.assertEqual(observation.position, BlockPos(10, 64, -3))
        self.assertEqual(observation.status, ObservationStatus.OK)
        self.assertEqual(observation.server_epoch, 4242)
        self.assertEqual(observation.server_tick, 88)
        self.assertEqual(observation.block.block_id, "minecraft:farmland")
        self.assertEqual(observation.block.properties["moisture"], "7")
        self.assertFalse(observation.block.air)

    def test_unloaded_block_is_not_air(self):
        self.server.block_handler = lambda request: make_block_observation(
            ROBOT_A,
            request["position"],
            status="UNLOADED",
            reason="chunk is not loaded",
            epoch=4242,
            tick=89,
        )

        observation = self.client.robot(ROBOT_A).observe_block(BlockPos(100000, 64, 100000))

        self.assertEqual(observation.status, ObservationStatus.UNLOADED)
        self.assertIsNone(observation.block)
        self.assertEqual(observation.reason, "chunk is not loaded")
        self.assertEqual(observation.server_epoch, 4242)
        self.assertEqual(observation.server_tick, 89)

    def test_observe_block_unknown_robot_is_explicit(self):
        self.server.error_for_op = {
            "op": "observe_block",
            "code": "ROBOT_NOT_FOUND",
            "message": "robot is not registered",
        }
        with self.assertRaises(RobotNotFound):
            self.client.robot(ROBOT_A).observe_block(BlockPos(0, 64, 0))

    def test_observe_block_unloaded_robot_is_explicit(self):
        self.server.error_for_op = {
            "op": "observe_block",
            "code": "ROBOT_NOT_LOADED",
            "message": "robot is not loaded",
        }
        with self.assertRaises(RobotNotLoaded) as caught:
            self.client.robot(ROBOT_A).observe_block(BlockPos(0, 64, 0))
        self.assertEqual(caught.exception.code, "ROBOT_NOT_LOADED")

    def test_observe_block_rejects_non_block_position(self):
        with self.assertRaises(ValueError):
            self.client.robot(ROBOT_A).observe_block({"x": 0, "y": 64, "z": 0})

    # ------------------------------------------------------------------
    # Acknowledge versus result
    # ------------------------------------------------------------------
    def test_ack_is_not_completion(self):
        handle = self.client.submit(ROBOT_A, Stop(), "c-ack")
        self.assertEqual(handle.ack.status, AckStatus.ACCEPTED)
        self.assertEqual(handle.state, CommandState.QUEUED)
        self.assertFalse(handle.is_terminal)
        self.assertIsNone(handle.result)

        self.server.push_events(
            [self.event(1, "COMMAND_ACCEPTED", ROBOT_A, "c-ack", "QUEUED")]
        )
        self.server.push_events(
            [self.event(2, "COMMAND_COMPLETED", ROBOT_A, "c-ack", "SUCCEEDED", message="done")]
        )
        result = handle.wait(2.0)
        self.assertTrue(result.ok)
        self.assertEqual(result.event_type, "COMMAND_COMPLETED")
        self.assertEqual(result.message, "done")
        self.assertFalse(result.from_ack)

    def test_wait_timeout_reports_current_state(self):
        handle = self.client.submit(ROBOT_A, Stop(), "c-timeout")
        with self.assertRaises(WaitTimeout) as caught:
            handle.wait(0.2)
        self.assertEqual(caught.exception.handle.state, CommandState.QUEUED)

    def test_pushed_event_before_ack_is_not_lost(self):
        first = self.client.submit(ROBOT_B, Stop(), "c-b")
        self.server.push_before_ack = [
            self.event(1, "COMMAND_COMPLETED", ROBOT_B, "c-b", "SUCCEEDED")
        ]
        self.client.submit(ROBOT_A, Stop(), "c-a")
        self.assertTrue(first.is_terminal)

    # ------------------------------------------------------------------
    # Cancel
    # ------------------------------------------------------------------
    def test_cancel_active_command_reaches_cancelled(self):
        handle = self.client.submit(ROBOT_A, Stop(), "c-cancel")
        self.server.push_events(
            [self.event(1, "COMMAND_STARTED", ROBOT_A, "c-cancel", "RUNNING")]
        )
        self.client.poll_events()
        self.assertEqual(handle.state, CommandState.RUNNING)

        ack = handle.cancel()
        self.assertEqual(ack.status, CancelStatus.ACCEPTED)
        self.server.push_events(
            [self.event(2, "COMMAND_CANCELLED", ROBOT_A, "c-cancel", "CANCELLED", detail="cancel request")]
        )
        result = handle.wait(2.0)
        self.assertTrue(result.state is CommandState.CANCELLED)
        self.assertTrue(handle.cancelled)

    def test_cancel_already_terminal_is_explicit(self):
        handle = self.client.submit(ROBOT_A, Stop(), "c-done")
        self.server.push_events(
            [self.event(1, "COMMAND_COMPLETED", ROBOT_A, "c-done", "SUCCEEDED")]
        )
        handle.wait(2.0)
        self.server.cancel_handler = lambda request: {
            "robotId": request["robot"],
            "correlationId": request["correlation"],
            "status": "ALREADY_TERMINAL",
            "state": "SUCCEEDED",
            "message": "command is already terminal",
        }
        ack = handle.cancel()
        self.assertEqual(ack.status, CancelStatus.ALREADY_TERMINAL)
        self.assertEqual(ack.state, CommandState.SUCCEEDED)

    def test_cancel_unknown_is_not_success(self):
        handle = self.client.submit(ROBOT_A, Stop(), "c-unknown")
        self.server.cancel_handler = lambda request: {
            "robotId": request["robot"],
            "correlationId": request["correlation"],
            "status": "UNKNOWN",
            "error": "UNKNOWN_CORRELATION",
            "message": "unknown correlationId",
        }
        with self.assertRaises(CancelUnknown):
            handle.cancel_and_wait(1.0)

    # ------------------------------------------------------------------
    # Duplicate and rejected submissions
    # ------------------------------------------------------------------
    def test_duplicate_terminal_ack_is_marked_terminal(self):
        handle = self.client.submit(ROBOT_A, Stop(), "c-dup")
        self.server.push_events(
            [self.event(1, "COMMAND_COMPLETED", ROBOT_A, "c-dup", "SUCCEEDED")]
        )
        handle.wait(2.0)

        self.server.submit_handler = lambda request: {
            "robotId": request["robot"],
            "correlationId": request["correlation"],
            "type": request["command"]["type"],
            "status": "DUPLICATE",
            "state": "SUCCEEDED",
            "message": "duplicate request",
        }
        duplicate = self.client.submit(ROBOT_A, Stop(), "c-dup")
        self.assertTrue(duplicate.is_terminal)
        self.assertEqual(duplicate.result.state, CommandState.SUCCEEDED)

    def test_duplicate_terminal_ack_without_retained_event_uses_ack(self):
        handle = self.client.submit(ROBOT_A, Stop(), "c-dup-2")
        self.server.push_events(
            [self.event(1, "COMMAND_COMPLETED", ROBOT_A, "c-dup-2", "SUCCEEDED")]
        )
        handle.wait(2.0)

        # A fresh connection whose retained ring no longer holds the event.
        self.server.event_log.clear()
        self.client.close()
        self.client = SwarmieClient(
            port=self.server.port, connect_timeout=2.0, request_timeout=1.0
        )
        self.server.submit_handler = lambda request: {
            "robotId": request["robot"],
            "correlationId": request["correlation"],
            "type": request["command"]["type"],
            "status": "DUPLICATE",
            "state": "SUCCEEDED",
            "error": None,
            "message": "duplicate request",
        }
        duplicate = self.client.submit(ROBOT_A, Stop(), "c-dup-2")
        self.assertTrue(duplicate.is_terminal)
        self.assertTrue(duplicate.result.from_ack)

    def test_duplicate_while_running_returns_the_same_handle(self):
        handle = self.client.submit(ROBOT_A, Stop(), "c-live")
        self.server.submit_handler = lambda request: {
            "robotId": request["robot"],
            "correlationId": request["correlation"],
            "type": request["command"]["type"],
            "status": "DUPLICATE",
            "state": "RUNNING",
            "message": "duplicate request",
        }
        again = self.client.submit(ROBOT_A, Stop(), "c-live")
        self.assertIs(again, handle)
        self.assertEqual(again.duplicate_ack.status, AckStatus.DUPLICATE)

    def test_rejected_submission_raises_with_structured_ack(self):
        self.server.submit_handler = lambda request: {
            "robotId": request["robot"],
            "correlationId": request["correlation"],
            "type": request["command"]["type"],
            "status": "REJECTED",
            "error": "ROBOT_NOT_LOADED",
            "message": "robot is not loaded",
        }
        with self.assertRaises(CommandRejected) as caught:
            self.client.submit(ROBOT_A, Stop(), "c-rejected")
        self.assertEqual(caught.exception.ack.error, "ROBOT_NOT_LOADED")

    # ------------------------------------------------------------------
    # Connection and session errors
    # ------------------------------------------------------------------
    def test_server_error_message_is_raised(self):
        self.server.error_for_op = {
            "op": "robots",
            "code": "INVALID_REQUEST",
            "message": "bad request",
        }
        with self.assertRaises(ServerError) as caught:
            self.client.robots()
        self.assertEqual(caught.exception.code, "INVALID_REQUEST")

    def test_connection_close_is_not_success(self):
        handle = self.client.submit(ROBOT_A, Stop(), "c-disconnect")
        self.server.disconnect()
        with self.assertRaises(ConnectionClosed):
            handle.wait(1.0)

    def test_same_epoch_reconnect_replays_terminal_event(self):
        handle = self.client.submit(ROBOT_A, Stop(), "c-resume")
        self.server.disconnect()
        # Delivered only to the event store while the client is away.
        self.server.push_events(
            [self.event(1, "COMMAND_COMPLETED", ROBOT_A, "c-resume", "SUCCEEDED")]
        )
        info = self.client.reconnect()
        self.assertTrue(info.resumed)
        self.assertEqual(info.epoch, 4242)
        self.assertTrue(handle.wait(2.0).ok)

    def test_new_epoch_invalidates_pending_handles(self):
        handle = self.client.submit(ROBOT_A, Stop(), "c-new-session")
        self.server.restart(9999)
        info = self.client.reconnect()
        self.assertFalse(info.resumed)
        self.assertEqual(info.epoch, 9999)
        with self.assertRaises(SessionChangedError):
            handle.wait(1.0)

    # ------------------------------------------------------------------
    # Event gaps and isolation
    # ------------------------------------------------------------------
    def test_gap_with_terminal_result_is_flagged(self):
        handle = self.client.submit(ROBOT_A, Stop(), "c-gap")
        self.server.push_events(
            [self.event(5, "COMMAND_COMPLETED", ROBOT_A, "c-gap", "SUCCEEDED")],
            missed=True,
        )
        result = handle.wait(2.0)
        self.assertTrue(result.ok)
        self.assertTrue(result.had_gap)
        self.assertTrue(self.client.missed)
        self.assertIsNotNone(self.client.gap)

    def test_gap_without_terminal_raises_instead_of_timeout(self):
        handle = self.client.submit(ROBOT_A, Stop(), "c-gap-2")
        self.server.push_events(
            [self.event(5, "COMMAND_COMPLETED", ROBOT_B, "c-other", "SUCCEEDED")],
            missed=True,
        )
        with self.assertRaises(EventGapError):
            handle.wait(1.0)

    def test_other_robot_events_are_not_dropped(self):
        first = self.client.submit(ROBOT_A, Stop(), "c-a")
        second = self.client.submit(ROBOT_B, Stop(), "c-b")
        self.server.push_events(
            [self.event(1, "COMMAND_COMPLETED", ROBOT_B, "c-b", "SUCCEEDED")]
        )
        self.server.push_events(
            [self.event(2, "COMMAND_COMPLETED", ROBOT_A, "c-a", "SUCCEEDED")]
        )
        self.assertTrue(first.wait(2.0).ok)
        self.assertTrue(second.wait(2.0).ok)

    def test_poll_events_advances_the_cursor(self):
        self.server.push_events(
            [self.event(1, "ROBOT_LOADED", ROBOT_A, None, None)]
        )
        first = self.client.poll_events()
        self.assertEqual(first.latest_sequence, 1)
        second = self.client.poll_events()
        self.assertTrue(second.empty)
        self.assertEqual(self.client.last_sequence, 1)


class InitialSyncTest(unittest.TestCase):
    """A fresh connection must baseline the cursor, not mistake history for a gap."""

    def test_existing_history_is_baselined(self):
        server = MockSwarmieServer(epoch=77, robots=[make_robot(ROBOT_A)]).start()
        server.push_events(
            [
                make_event(77, 1, "ROBOT_LOADED", ROBOT_A),
                make_event(
                    77,
                    2,
                    "COMMAND_COMPLETED",
                    ROBOT_A,
                    correlation="old",
                    state="SUCCEEDED",
                ),
            ]
        )
        client = SwarmieClient(port=server.port, request_timeout=1.0)
        try:
            self.assertFalse(client.missed)
            self.assertEqual(client.last_sequence, 2)
            handle = client.submit(ROBOT_A, Stop(), "c-new")
            server.push_events(
                [
                    make_event(
                        77,
                        3,
                        "COMMAND_COMPLETED",
                        ROBOT_A,
                        correlation="c-new",
                        state="SUCCEEDED",
                    )
                ]
            )
            self.assertTrue(handle.wait(2.0).ok)
            self.assertFalse(client.missed)
        finally:
            client.close()
            server.stop()

    def test_evicted_history_at_connect_baselines_without_gap(self):
        server = MockSwarmieServer(epoch=88, robots=[make_robot(ROBOT_A)]).start()
        server.push_events(
            [
                make_event(88, 50, "ROBOT_LOADED", ROBOT_A),
                make_event(88, 51, "ROBOT_LOADED", ROBOT_A),
            ]
        )
        client = SwarmieClient(port=server.port, request_timeout=1.0)
        try:
            self.assertFalse(client.missed)
            self.assertEqual(client.last_sequence, 51)
        finally:
            client.close()
            server.stop()


if __name__ == "__main__":
    unittest.main()
