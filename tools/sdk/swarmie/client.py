"""High-level synchronous Swarmie client.

The client deliberately exposes one explicit robot at a time: :class:`Robot`
objects are only created by UUID lookup, never by picking the first loaded
robot. All asynchronous command state is carried by :class:`CommandHandle`.
"""
from __future__ import annotations

import itertools
import math
import os
import time
from collections import deque
from dataclasses import replace
from typing import Any, Deque, Dict, List, Optional

from . import protocol
from .errors import (
    CancelUnknown,
    CommandRejected,
    ConnectionClosed,
    EventGapError,
    ProtocolError,
    RequestTimeout,
    RobotNotFound,
    RobotNotLoaded,
    ServerError,
    SessionChangedError,
    SetpointRejected,
    WaitTimeout,
)
from .models import (
    Ack,
    AckStatus,
    CancelAck,
    CancelStatus,
    CommandResult,
    CommandState,
    Gap,
    RobotInfo,
    RobotLifecycle,
    RobotState,
    SessionInfo,
    SetpointAck,
    Vec3,
    normalize_robot_id,
    validate_correlation,
)
from .transport import JsonlTransport

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 25580
DEFAULT_CONNECT_TIMEOUT = 5.0
DEFAULT_REQUEST_TIMEOUT = 5.0


class SwarmieClient:
    """One JSONL connection to the local Swarmie Adapter."""

    def __init__(
        self,
        host: str = DEFAULT_HOST,
        port: int = DEFAULT_PORT,
        connect_timeout: float = DEFAULT_CONNECT_TIMEOUT,
        request_timeout: float = DEFAULT_REQUEST_TIMEOUT,
        event_buffer: int = 2048,
    ) -> None:
        self.host = host
        self.port = int(port)
        self.connect_timeout = float(connect_timeout)
        self.request_timeout = float(request_timeout)
        self.protocol: str = ""
        self.epoch: int = 0
        self.last_sequence: int = 0
        self.missed: bool = False
        self.gap: Optional[Gap] = None
        self._closed = False
        self._next_request_id = itertools.count(1)
        self._next_correlation = itertools.count(1)
        self._pending: Dict[str, "CommandHandle"] = {}
        self._events: Deque = deque(maxlen=max(1, int(event_buffer)))
        self._syncing = True
        self._transport = JsonlTransport(host, self.port, self.connect_timeout)
        self._hello()
        self._initial_sync()

    @classmethod
    def from_env(cls, **kwargs: Any) -> "SwarmieClient":
        """Connect using ``SWARMIE_HOST``/``SWARMIE_PORT`` when set."""
        host = os.environ.get("SWARMIE_HOST", DEFAULT_HOST)
        port = int(os.environ.get("SWARMIE_PORT", str(DEFAULT_PORT)))
        return cls(host=host, port=port, **kwargs)

    # ------------------------------------------------------------------
    # Connection and session
    # ------------------------------------------------------------------
    @property
    def connected(self) -> bool:
        return not self._closed and self._transport.connected

    @property
    def server_epoch(self) -> int:
        return self.epoch

    def _hello(self, timeout: Optional[float] = None) -> None:
        message = self._transport.receive(self.connect_timeout if timeout is None else timeout)
        if message.get("type") != "hello":
            raise ProtocolError(f"expected hello, got {message.get('type')!r}")
        version = message.get("protocol")
        if version != protocol.HELLO_PROTOCOL:
            raise ProtocolError(f"unsupported JSONL protocol {version!r}")
        epoch = message.get("epoch")
        if isinstance(epoch, bool) or not isinstance(epoch, int) or epoch < 0:
            raise ProtocolError("hello.epoch must be a non-negative integer")
        self.protocol = version
        self.epoch = epoch

    def close(self) -> None:
        self._closed = True
        self._transport.close()

    def __enter__(self) -> "SwarmieClient":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()

    def reconnect(
        self,
        connect_timeout: Optional[float] = None,
        request_timeout: Optional[float] = None,
    ) -> SessionInfo:
        """Reconnect and resynchronize; never pretends lost events were filled."""
        old_epoch = self.epoch
        self._transport.close()
        self._closed = False
        self._transport = JsonlTransport(
            self.host, self.port, self.connect_timeout if connect_timeout is None else connect_timeout
        )
        self._hello(connect_timeout)

        if self.epoch != old_epoch:
            # A different server session: every pending command is unknowable.
            for handle in self._pending.values():
                handle._session_changed = True
            self._pending.clear()
            self.last_sequence = 0
            self.missed = False
            self.gap = None
            self._events.clear()
            self._initial_sync()
            return SessionInfo(
                epoch=self.epoch,
                protocol=self.protocol,
                resumed=False,
                missed=False,
                gap=None,
            )

        timeout = self.request_timeout if request_timeout is None else request_timeout
        while True:
            batch = self._poll_batch(timeout=timeout, max_events=256)
            if not batch.events or len(batch.events) < 256:
                break
            if batch.next_sequence <= self.last_sequence + 1:
                break
        return SessionInfo(
            epoch=self.epoch,
            protocol=self.protocol,
            resumed=True,
            missed=self.missed,
            gap=self.gap,
        )

    # ------------------------------------------------------------------
    # Discovery
    # ------------------------------------------------------------------
    def robots(self, timeout: Optional[float] = None) -> List[RobotInfo]:
        response = self._request({"op": "robots"}, "robots", timeout)
        epoch = response.get("epoch")
        if isinstance(epoch, bool) or not isinstance(epoch, int):
            raise ProtocolError("robots.epoch must be an integer")
        if epoch != self.epoch:
            raise SessionChangedError(self.epoch, epoch)
        items = response.get("robots")
        if not isinstance(items, list):
            raise ProtocolError("robots.robots must be an array")
        return [protocol.parse_robot_info(item) for item in items]

    def robot(self, robot_id: str, timeout: Optional[float] = None) -> "Robot":
        """Select a robot by exact UUID (no implicit first-loaded fallback)."""
        wanted = normalize_robot_id(robot_id)
        for info in self.robots(timeout=timeout):
            if info.robot_id == wanted:
                return Robot(self, info)
        raise RobotNotFound(wanted)

    def state(self, robot_id: str, timeout: Optional[float] = None) -> RobotState:
        """Read a server-authoritative live snapshot for one exact UUID."""
        rid = normalize_robot_id(robot_id)
        try:
            response = self._request(
                {"op": "state", "robot": rid},
                "state",
                timeout,
            )
        except ServerError as exc:
            if exc.code == "ROBOT_NOT_FOUND":
                raise RobotNotFound(rid) from exc
            if exc.code in {"ROBOT_NOT_LOADED", "ROBOT_REMOVED", "ROBOT_UNAVAILABLE"}:
                raise RobotNotLoaded(rid, exc.code) from exc
            raise
        return protocol.parse_state(response.get("state"))

    def set_velocity(
        self,
        robot_id: str,
        velocity: Vec3,
        timeout: Optional[float] = None,
    ) -> SetpointAck:
        """Accept or replace the persistent velocity target (not an action)."""
        rid = normalize_robot_id(robot_id)
        if not isinstance(velocity, Vec3):
            raise ValueError("velocity must be a Vec3")
        for axis in (velocity.x, velocity.y, velocity.z):
            if not math.isfinite(float(axis)):
                raise ValueError("velocity components must be finite")
        return self._setpoint(rid, {"value": velocity.to_wire()}, timeout)

    def clear_velocity(
        self,
        robot_id: str,
        timeout: Optional[float] = None,
    ) -> SetpointAck:
        """Release the velocity target; this is not a queued Stop action."""
        rid = normalize_robot_id(robot_id)
        return self._setpoint(rid, {"clear": True}, timeout)

    def _setpoint(
        self,
        robot_id: str,
        payload: Dict[str, Any],
        timeout: Optional[float],
    ) -> SetpointAck:
        request: Dict[str, Any] = {
            "op": "setpoint",
            "robot": robot_id,
            "channel": "velocity",
        }
        request.update(payload)
        response = self._request(request, "setpoint_ack", timeout)
        ack = protocol.parse_setpoint_ack(response.get("ack"))
        if not ack.accepted:
            if ack.error == "ROBOT_NOT_FOUND":
                raise RobotNotFound(robot_id)
            if ack.error in {"ROBOT_NOT_LOADED", "ROBOT_REMOVED", "ROBOT_UNAVAILABLE"}:
                raise RobotNotLoaded(robot_id, ack.error or "ROBOT_NOT_LOADED")
            raise SetpointRejected(ack)
        return ack

    # ------------------------------------------------------------------
    # Commands
    # ------------------------------------------------------------------
    def submit(
        self,
        robot_id: str,
        command: Any,
        correlation: Optional[str] = None,
        timeout: Optional[float] = None,
    ) -> "CommandHandle":
        rid = normalize_robot_id(robot_id)
        correlation_id = validate_correlation(
            correlation if correlation is not None else self._new_correlation(rid)
        )
        wire = protocol.encode_command(command)
        existing = self._pending.get(correlation_id)
        if existing is not None and existing.is_terminal:
            existing = None

        response = self._request(
            {
                "op": "submit",
                "robot": rid,
                "correlation": correlation_id,
                "command": wire,
            },
            "ack",
            timeout,
        )
        ack = protocol.parse_ack(response.get("ack"))
        if ack.status is AckStatus.REJECTED:
            raise CommandRejected(ack)

        if existing is not None:
            # Same live correlation: the server returned DUPLICATE; keep one handle.
            existing.duplicate_ack = ack
            return existing

        handle = CommandHandle(self, rid, correlation_id, wire.get("type", ""), ack)
        for event in list(self._events):
            if event.correlation_id == correlation_id:
                handle._apply_event(event)

        if ack.status is AckStatus.DUPLICATE and ack.state is not None and ack.state.is_terminal:
            if not handle.is_terminal:
                handle._apply_ack_terminal(ack)

        if not handle.is_terminal:
            self._pending[correlation_id] = handle
        return handle

    def cancel(
        self,
        robot_id: str,
        correlation: str,
        timeout: Optional[float] = None,
    ) -> CancelAck:
        rid = normalize_robot_id(robot_id)
        correlation_id = validate_correlation(correlation)
        response = self._request(
            {"op": "cancel", "robot": rid, "correlation": correlation_id},
            "cancel_ack",
            timeout,
        )
        ack = protocol.parse_cancel_ack(response.get("cancelAck"))
        if ack.status is CancelStatus.REJECTED:
            from .errors import CancelRejected

            raise CancelRejected(ack)
        return ack

    def poll_events(self, max_events: int = 64, timeout: Optional[float] = None):
        return self._poll_batch(timeout=timeout, max_events=max_events)

    # ------------------------------------------------------------------
    # Internals used by CommandHandle
    # ------------------------------------------------------------------
    def _poll_batch(self, timeout: Optional[float], max_events: int):
        if not 1 <= int(max_events) <= 256:
            raise ValueError("max_events must be in 1..256")
        response = self._request(
            {"op": "events", "after": self.last_sequence, "max": int(max_events)},
            "events",
            timeout,
        )
        batch = protocol.parse_batch(response.get("batch"))
        return batch

    def _request(
        self,
        payload: Dict[str, Any],
        expected: str,
        timeout: Optional[float] = None,
    ) -> Dict[str, Any]:
        if self._closed or not self._transport.connected:
            raise ConnectionClosed("client is not connected")
        timeout = self.request_timeout if timeout is None else float(timeout)
        if timeout < 0:
            raise ValueError("timeout must be non-negative")
        request_id = str(next(self._next_request_id))
        message = dict(payload)
        message["id"] = request_id
        self._transport.send(message)

        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RequestTimeout(f"request {payload.get('op')!r} timed out")
            try:
                incoming = self._transport.receive(remaining)
            except RequestTimeout:
                raise RequestTimeout(f"request {payload.get('op')!r} timed out")

            kind = incoming.get("type")
            incoming_id = incoming.get("id")

            if kind == "events":
                self._handle_batch(incoming.get("batch"))
                if incoming_id == request_id:
                    return incoming
                continue
            if kind == "pong" and incoming_id is None:
                continue
            if kind == "error" and incoming_id is None:
                raise ServerError(
                    str(incoming.get("code", "UNKNOWN")),
                    str(incoming.get("message", "")),
                    None,
                )
            if incoming_id == request_id:
                if kind == "error":
                    raise ServerError(
                        str(incoming.get("code", "UNKNOWN")),
                        str(incoming.get("message", "")),
                        request_id,
                    )
                if kind != expected:
                    raise ProtocolError(f"expected {expected!r}, got {kind!r}")
                return incoming
            raise ProtocolError(
                f"unexpected message id {incoming_id!r} while waiting for request {request_id!r}"
            )

    def _dispatch_message(self, message: Dict[str, Any]) -> None:
        kind = message.get("type")
        if kind == "events":
            self._handle_batch(message.get("batch"))
            return
        if kind == "pong":
            return
        if kind == "error":
            raise ServerError(
                str(message.get("code", "UNKNOWN")),
                str(message.get("message", "")),
                message.get("id"),
            )
        raise ProtocolError(f"unexpected unsolicited message: {kind!r}")

    def _handle_batch(self, raw: Any) -> None:
        batch = protocol.parse_batch(raw)
        if batch.server_epoch != self.epoch:
            raise SessionChangedError(self.epoch, batch.server_epoch)
        if batch.missed:
            if self._syncing:
                # A new connection has no cursor yet: baseline at the oldest
                # retained event instead of reporting a gap we never observed.
                baseline = max(0, batch.oldest_available_sequence - 1)
                if self.last_sequence < baseline:
                    self.last_sequence = baseline
            else:
                self.missed = True
                if self.gap is None:
                    self.gap = Gap(batch.after_sequence, batch.oldest_available_sequence)

        expected_sequence = self.last_sequence + 1
        for event in batch.events:
            if event.sequence < expected_sequence:
                continue  # replay overlap after reconnect
            if event.sequence > expected_sequence and not self._syncing:
                self.missed = True
                if self.gap is None:
                    self.gap = Gap(expected_sequence - 1, event.sequence)
            expected_sequence = event.sequence + 1
            self.last_sequence = event.sequence
            self._events.append(event)
            handle = self._pending.get(event.correlation_id or "")
            if handle is not None:
                handle._apply_event(event)

    def _initial_sync(self) -> None:
        """Baseline the cursor against the retained event store on connect."""
        self._syncing = True
        try:
            while True:
                batch = self._poll_batch(timeout=self.request_timeout, max_events=256)
                if not batch.events:
                    break
                if batch.next_sequence <= self.last_sequence + 1:
                    break
                if len(batch.events) < 256:
                    break
        finally:
            self._syncing = False
        self.missed = False
        self.gap = None

    def _new_correlation(self, robot_id: str) -> str:
        return "sdk-%s-%x-%x" % (
            robot_id[:8],
            int(time.time() * 1000),
            next(self._next_correlation),
        )


class Robot:
    """A single robot identified by an explicit UUID."""

    def __init__(self, client: SwarmieClient, info: RobotInfo) -> None:
        self._client = client
        self.info = info
        self.robot_id = info.robot_id

    @property
    def lifecycle(self) -> RobotLifecycle:
        return self.info.lifecycle

    def refresh(self) -> RobotInfo:
        for info in self._client.robots():
            if info.robot_id == self.robot_id:
                self.info = info
                return info
        raise RobotNotFound(self.robot_id)

    def status(self) -> RobotInfo:
        return self.refresh()

    def state(self, timeout: Optional[float] = None) -> RobotState:
        """Return live state, distinct from the registry metadata in ``info``."""
        return self._client.state(self.robot_id, timeout=timeout)

    def set_velocity(self, velocity: Vec3, timeout: Optional[float] = None) -> SetpointAck:
        """Set or replace the persistent velocity target."""
        return self._client.set_velocity(self.robot_id, velocity, timeout=timeout)

    def clear_velocity(self, timeout: Optional[float] = None) -> SetpointAck:
        """Clear the persistent velocity target."""
        return self._client.clear_velocity(self.robot_id, timeout=timeout)

    def submit(self, command: Any, correlation: Optional[str] = None) -> "CommandHandle":
        return self._client.submit(self.robot_id, command, correlation)

    def __repr__(self) -> str:
        return f"Robot({self.robot_id}, {self.info.lifecycle.value})"


class CommandHandle:
    """A submitted command; acknowledges are not results."""

    def __init__(
        self,
        client: SwarmieClient,
        robot_id: str,
        correlation_id: str,
        command_type: str,
        ack: Ack,
    ) -> None:
        self._client = client
        self.robot_id = robot_id
        self.correlation_id = correlation_id
        self.command_type = command_type
        self.ack = ack
        self.duplicate_ack: Optional[Ack] = None
        self.state: CommandState = ack.state or CommandState.QUEUED
        self.result: Optional[CommandResult] = None
        self._epoch = client.epoch
        self._session_changed = False

    @property
    def is_terminal(self) -> bool:
        return self.result is not None and self.result.state.is_terminal

    @property
    def succeeded(self) -> bool:
        return self.result is not None and self.result.state is CommandState.SUCCEEDED

    @property
    def failed(self) -> bool:
        return self.result is not None and self.result.state is CommandState.FAILED

    @property
    def cancelled(self) -> bool:
        return self.result is not None and self.result.state is CommandState.CANCELLED

    @property
    def interrupted(self) -> bool:
        return self.result is not None and self.result.state is CommandState.INTERRUPTED

    def wait(self, timeout: float = 10.0) -> CommandResult:
        """Block until a terminal event, an explicit gap/session error or timeout."""
        timeout = float(timeout)
        if timeout < 0:
            raise ValueError("timeout must be non-negative")
        if self.is_terminal:
            return self.result  # type: ignore[return-value]
        if self._session_changed:
            raise SessionChangedError(self._epoch, self._client.epoch)
        if self._client.epoch != self._epoch:
            raise SessionChangedError(self._epoch, self._client.epoch)

        deadline = time.monotonic() + timeout
        while True:
            if self.is_terminal:
                return self.result  # type: ignore[return-value]
            if self._session_changed or self._client.epoch != self._epoch:
                raise SessionChangedError(self._epoch, self._client.epoch)
            if self._client.missed:
                gap = self._client.gap
                raise EventGapError(
                    gap.after_sequence if gap else self._client.last_sequence,
                    gap.oldest_available_sequence if gap else self._client.last_sequence,
                )
            if not self._client.connected:
                raise ConnectionClosed(
                    f"connection lost while waiting for {self.correlation_id}"
                )
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise WaitTimeout(self, timeout)
            try:
                message = self._client._transport.receive(remaining)
            except RequestTimeout:
                if self.is_terminal:
                    return self.result  # type: ignore[return-value]
                raise WaitTimeout(self, timeout)
            self._client._dispatch_message(message)

    def cancel(self) -> CancelAck:
        ack = self._client.cancel(self.robot_id, self.correlation_id)
        if ack.status is CancelStatus.ALREADY_TERMINAL and ack.state is not None:
            self.state = ack.state
            if not self.is_terminal:
                self._apply_ack_terminal(ack)
        return ack

    def cancel_and_wait(self, timeout: float = 10.0) -> CommandResult:
        ack = self.cancel()
        if ack.status is CancelStatus.UNKNOWN:
            raise CancelUnknown(ack)
        return self.wait(timeout)

    def _apply_event(self, event) -> None:
        if event.correlation_id != self.correlation_id:
            return
        result = event.command_result
        if result is None:
            return
        self.state = result.state
        if result.state.is_terminal:
            self.result = replace(
                result,
                event_type=event.type.value,
                sequence=event.sequence,
                server_tick=event.server_tick,
                from_ack=False,
                had_gap=self._client.missed,
            )
            self._client._pending.pop(self.correlation_id, None)

    def _apply_ack_terminal(self, ack: Ack) -> None:
        if ack.state is None or not ack.state.is_terminal:
            return
        self.state = ack.state
        self.result = CommandResult(
            robot_id=self.robot_id,
            correlation_id=self.correlation_id,
            internal_command_id=ack.internal_command_id,
            state=ack.state,
            error=ack.error,
            message=ack.message,
            from_ack=True,
            had_gap=self._client.missed,
        )

    def __repr__(self) -> str:
        return (
            f"CommandHandle({self.correlation_id}, {self.state.value}, "
            f"terminal={self.is_terminal})"
        )
