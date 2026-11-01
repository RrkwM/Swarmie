"""A controllable in-process JSONL server for SDK tests.

It speaks only the documented localhost protocol; tests decide when events are
pushed, when the connection drops and when the epoch changes.
"""
from __future__ import annotations

import json
import socket
import threading
import time
from typing import Any, Callable, Dict, List, Optional


def make_robot(
    robot_id: str,
    lifecycle: str = "LOADED",
    dimension: str = "minecraft:overworld",
    x: float = 0.0,
    y: float = 64.0,
    z: float = 0.0,
    commands: Optional[List[str]] = None,
) -> Dict[str, Any]:
    return {
        "robotId": robot_id,
        "dimensionId": dimension,
        "position": {"x": x, "y": y, "z": z},
        "lifecycle": lifecycle,
        "supportedCommands": commands
        or ["TIMED_FLIGHT", "STOP", "MINE_BLOCK", "USE_ITEM_ON_BLOCK"],
        "lastUpdatedTick": 1,
    }


def make_state(
    robot_id: str,
    epoch: int = 424242,
    tick: int = 1,
    dimension: str = "minecraft:overworld",
    x: float = 0.0,
    y: float = 64.0,
    z: float = 0.0,
    vx: float = 0.0,
    vy: float = 0.0,
    vz: float = 0.0,
    yaw: float = 0.0,
    pitch: float = 0.0,
    lifecycle: str = "LOADED",
) -> Dict[str, Any]:
    return {
        "robotId": robot_id,
        "serverEpoch": epoch,
        "serverTick": tick,
        "dimensionId": dimension,
        "position": {"x": x, "y": y, "z": z},
        "velocity": {"x": vx, "y": vy, "z": vz},
        "yaw": yaw,
        "pitch": pitch,
        "lifecycle": lifecycle,
    }


def make_event(
    epoch: int,
    sequence: int,
    event_type: str,
    robot_id: str,
    correlation: Optional[str] = None,
    state: Optional[str] = None,
    error: Optional[str] = None,
    message: str = "",
    detail: str = "",
    server_tick: int = 0,
) -> Dict[str, Any]:
    event: Dict[str, Any] = {
        "eventId": f"{epoch}:{sequence}",
        "serverEpoch": epoch,
        "sequence": sequence,
        "serverTick": server_tick,
        "type": event_type,
        "robotId": robot_id,
        "detail": detail,
    }
    if correlation is not None:
        event["correlationId"] = correlation
    if state is not None:
        result: Dict[str, Any] = {
            "robotId": robot_id,
            "correlationId": correlation or "",
            "internalCommandId": "1",
            "state": state,
            "message": message,
        }
        if error is not None:
            result["error"] = error
        event["commandResult"] = result
    return event


def make_batch(
    events: List[Dict[str, Any]],
    epoch: int,
    after: int,
    oldest: int,
    latest: int,
    next_sequence: int,
    missed: bool,
) -> Dict[str, Any]:
    return {
        "serverEpoch": epoch,
        "afterSequence": after,
        "oldestAvailableSequence": oldest,
        "latestSequence": latest,
        "nextSequence": next_sequence,
        "missed": missed,
        "events": events,
    }


class MockSwarmieServer:
    def __init__(self, epoch: int = 424242, robots: Optional[List[Dict[str, Any]]] = None):
        self.epoch = epoch
        self.robots = robots or []
        self.requests: List[Dict[str, Any]] = []
        self.event_log: List[Dict[str, Any]] = []
        self.submit_handler: Optional[Callable[[Dict[str, Any]], Dict[str, Any]]] = None
        self.cancel_handler: Optional[Callable[[Dict[str, Any]], Dict[str, Any]]] = None
        self.events_handler: Optional[Callable[[Dict[str, Any]], Dict[str, Any]]] = None
        self.robots_handler: Optional[Callable[[], List[Dict[str, Any]]]] = None
        self.state_handler: Optional[Callable[[Dict[str, Any]], Dict[str, Any]]] = None
        self.setpoint_handler: Optional[Callable[[Dict[str, Any]], Dict[str, Any]]] = None
        self.error_for_op: Optional[Dict[str, Any]] = None
        self.push_before_ack: Optional[List[Dict[str, Any]]] = None

        self._lock = threading.RLock()
        self._stop = threading.Event()
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._sock.bind(("127.0.0.1", 0))
        self._sock.listen(2)
        self.port = self._sock.getsockname()[1]
        self._conn: Optional[socket.socket] = None
        self._thread = threading.Thread(target=self._serve, name="mock-swarmie", daemon=True)
        self._connected = threading.Event()
        self._submit_count = 0

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------
    def start(self) -> "MockSwarmieServer":
        self._thread.start()
        return self

    def stop(self) -> None:
        self._stop.set()
        self.disconnect()
        try:
            self._sock.close()
        except OSError:
            pass
        self._thread.join(timeout=2.0)

    def __enter__(self) -> "MockSwarmieServer":
        return self.start()

    def __exit__(self, exc_type, exc, tb) -> None:
        self.stop()

    def wait_connected(self, timeout: float = 2.0) -> None:
        if not self._connected.wait(timeout):
            raise AssertionError("client did not connect to the mock server")

    def disconnect(self) -> None:
        with self._lock:
            conn, self._conn = self._conn, None
        if conn is not None:
            try:
                conn.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                conn.close()
            except OSError:
                pass
        self._connected.clear()

    def restart(self, epoch: int) -> None:
        """Drop the connection; the next client gets ``epoch`` and an empty store."""
        self.epoch = epoch
        with self._lock:
            self.event_log.clear()
        self.disconnect()

    # ------------------------------------------------------------------
    # Outgoing messages
    # ------------------------------------------------------------------
    def push_events(self, events: List[Dict[str, Any]], missed: bool = False) -> None:
        if not events:
            return
        with self._lock:
            for event in events:
                if not any(
                    existing["sequence"] == event["sequence"]
                    for existing in self.event_log
                ):
                    self.event_log.append(event)
            self.event_log.sort(key=lambda item: item["sequence"])
            oldest = self.event_log[0]["sequence"]
            latest = self.event_log[-1]["sequence"]
            after = events[0]["sequence"] - 1
            batch = make_batch(
                events, self.epoch, after, oldest, latest, latest + 1, missed
            )
            self._send_locked({"type": "events", "batch": batch})

    def send_raw(self, text: str) -> None:
        with self._lock:
            self._send_text_locked(text)

    def send_error(self, request_id: Optional[str], code: str, message: str) -> None:
        with self._lock:
            payload: Dict[str, Any] = {"type": "error", "code": code, "message": message}
            if request_id is not None:
                payload["id"] = request_id
            self._send_locked(payload)

    # ------------------------------------------------------------------
    # Server loop
    # ------------------------------------------------------------------
    def _serve(self) -> None:
        while not self._stop.is_set():
            try:
                conn, _ = self._sock.accept()
            except OSError:
                return
            with self._lock:
                self._conn = conn
            self._connected.set()
            try:
                self._send_locked(
                    {
                        "type": "hello",
                        "protocol": "swarmie-jsonl/1",
                        "epoch": self.epoch,
                    }
                )
                reader = conn.makefile("r", encoding="utf-8", newline="\n")
                while not self._stop.is_set():
                    line = reader.readline()
                    if not line:
                        break
                    if not line.strip():
                        continue
                    request = json.loads(line)
                    self.requests.append(request)
                    self._handle(request)
            except (OSError, ValueError):
                pass
            finally:
                try:
                    conn.close()
                except OSError:
                    pass
                with self._lock:
                    if self._conn is conn:
                        self._conn = None
                self._connected.clear()

    def _handle(self, request: Dict[str, Any]) -> None:
        op = request.get("op")
        request_id = request.get("id")
        if self.error_for_op and self.error_for_op.get("op") == op:
            self.send_error(self.error_for_op.get("id", request_id), self.error_for_op["code"], self.error_for_op["message"])
            return
        if op == "robots":
            robots = self.robots_handler() if self.robots_handler else self.robots
            self._send_locked({"type": "robots", "id": request_id, "epoch": self.epoch, "robots": robots})
            return
        if op == "events":
            if self.events_handler is not None:
                batch = self.events_handler(request)
            else:
                batch = self._batch_for(
                    int(request.get("after", 0)), int(request.get("max", 64))
                )
            self._send_locked({"type": "events", "id": request_id, "batch": batch})
            return
        if op == "state":
            if self.state_handler is not None:
                state = self.state_handler(request)
                self._send_locked({"type": "state", "id": request_id, "state": state})
                return
            robot = self._find_robot(str(request.get("robot", "")))
            if robot is None:
                self.send_error(request_id, "ROBOT_NOT_FOUND", "robot is not registered")
                return
            if robot["lifecycle"] != "LOADED":
                code = "ROBOT_REMOVED" if robot["lifecycle"] == "REMOVED" else "ROBOT_NOT_LOADED"
                self.send_error(request_id, code, "robot is not available")
                return
            self._send_locked({
                "type": "state",
                "id": request_id,
                "state": make_state(
                    robot["robotId"],
                    epoch=self.epoch,
                    dimension=robot["dimensionId"],
                    x=robot["position"]["x"],
                    y=robot["position"]["y"],
                    z=robot["position"]["z"],
                    lifecycle=robot["lifecycle"],
                ),
            })
            return
        if op == "setpoint":
            ack = (
                self.setpoint_handler(request)
                if self.setpoint_handler is not None
                else self._default_setpoint_ack(request)
            )
            self._send_locked({"type": "setpoint_ack", "id": request_id, "ack": ack})
            return
        if op == "submit":
            with self._lock:
                self._submit_count += 1
            ack = (
                self.submit_handler(request)
                if self.submit_handler is not None
                else self._default_submit_ack(request)
            )
            if self.push_before_ack:
                pending, self.push_before_ack = self.push_before_ack, None
                self.push_events(pending)
            self._send_locked({"type": "ack", "id": request_id, "ack": ack})
            return
        if op == "cancel":
            ack = (
                self.cancel_handler(request)
                if self.cancel_handler is not None
                else self._default_cancel_ack(request)
            )
            self._send_locked({"type": "cancel_ack", "id": request_id, "cancelAck": ack})
            return
        if op == "ping":
            self._send_locked({"type": "pong"})
            return
        self.send_error(request_id, "UNKNOWN_OP", f"unknown op: {op!r}")

    def _default_submit_ack(self, request: Dict[str, Any]) -> Dict[str, Any]:
        command = request.get("command") or {}
        return {
            "robotId": request.get("robot"),
            "correlationId": request.get("correlation"),
            "type": command.get("type"),
            "status": "ACCEPTED",
            "internalCommandId": "1",
            "state": "QUEUED",
            "message": "",
        }

    def _find_robot(self, robot_id: str) -> Optional[Dict[str, Any]]:
        for robot in self.robots:
            if robot.get("robotId") == robot_id:
                return robot
        return None

    def _default_setpoint_ack(self, request: Dict[str, Any]) -> Dict[str, Any]:
        clear = bool(request.get("clear"))
        value = request.get("value") or {"x": 0.0, "y": 0.0, "z": 0.0}
        return {
            "robotId": request.get("robot"),
            "channel": request.get("channel", "velocity"),
            "revision": 1,
            "active": not clear,
            "accepted": True,
            "effectiveVelocity": None if clear else value,
            "clamped": False,
            "serverEpoch": self.epoch,
            "serverTick": 1,
            "message": "cleared" if clear else "",
        }

    def _default_cancel_ack(self, request: Dict[str, Any]) -> Dict[str, Any]:
        return {
            "robotId": request.get("robot"),
            "correlationId": request.get("correlation"),
            "status": "ACCEPTED",
            "state": "RUNNING",
            "message": "cancellation queued for the server thread",
        }

    def _batch_for(self, after: int, max_events: int) -> Dict[str, Any]:
        with self._lock:
            log = list(self.event_log)
        latest = log[-1]["sequence"] if log else 0
        oldest = log[0]["sequence"] if log else 1
        if not log or after >= latest:
            future = after > latest
            next_sequence = after if future else latest + 1
            return make_batch([], self.epoch, after, oldest, latest, next_sequence, future)
        missed = after < oldest - 1
        selected = [event for event in log if event["sequence"] > after][:max_events]
        next_sequence = selected[-1]["sequence"] + 1 if selected else latest + 1
        return make_batch(selected, self.epoch, after, oldest, latest, next_sequence, missed)

    def _send_locked(self, payload: Dict[str, Any]) -> None:
        self._send_text_locked(json.dumps(payload, separators=(",", ":")))

    def _send_text_locked(self, text: str) -> None:
        conn = self._conn
        if conn is None:
            return
        try:
            conn.sendall((text + "\n").encode("utf-8"))
        except OSError:
            pass


def wait_until(predicate: Callable[[], bool], timeout: float = 2.0, interval: float = 0.01) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(interval)
    raise AssertionError("condition was not met before the deadline")
