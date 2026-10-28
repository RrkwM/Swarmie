"""JSON message parsing and encoding for the Swarmie JSONL protocol.

Only the documented wire shape is accepted. Missing required fields raise
``ProtocolError``; unknown fields are ignored.
"""
from __future__ import annotations

from typing import Any, Dict, List, Optional

from .errors import ProtocolError
from .models import (
    Ack,
    AckStatus,
    CancelAck,
    CancelStatus,
    CommandResult,
    CommandState,
    CommandType,
    EventBatch,
    EventType,
    RobotEvent,
    RobotInfo,
    RobotLifecycle,
    Vec3,
    normalize_robot_id,
    parse_enum,
)

HELLO_PROTOCOL = "swarmie-jsonl/1"


def _object(value: Any, what: str) -> Dict[str, Any]:
    if not isinstance(value, dict):
        raise ProtocolError(f"{what} must be a JSON object, got {type(value).__name__}")
    return value


def _string(obj: Dict[str, Any], key: str, what: str) -> str:
    value = obj.get(key)
    if not isinstance(value, str) or value == "":
        raise ProtocolError(f"{what}.{key} must be a non-empty string")
    return value


def _optional_string(obj: Dict[str, Any], key: str) -> Optional[str]:
    value = obj.get(key)
    if value is None:
        return None
    if not isinstance(value, str):
        raise ProtocolError(f"{key} must be a string when present")
    return value


def _integer(obj: Dict[str, Any], key: str, what: str) -> int:
    value = obj.get(key)
    if isinstance(value, bool) or not isinstance(value, int):
        raise ProtocolError(f"{what}.{key} must be an integer")
    return value


def _boolean(obj: Dict[str, Any], key: str, what: str) -> bool:
    value = obj.get(key)
    if not isinstance(value, bool):
        raise ProtocolError(f"{what}.{key} must be a boolean")
    return value


def _array(obj: Dict[str, Any], key: str, what: str) -> List[Any]:
    value = obj.get(key)
    if not isinstance(value, list):
        raise ProtocolError(f"{what}.{key} must be an array")
    return value


def parse_vec3(value: Any, what: str) -> Vec3:
    obj = _object(value, what)
    for key in ("x", "y", "z"):
        component = obj.get(key)
        if isinstance(component, bool) or not isinstance(component, (int, float)):
            raise ProtocolError(f"{what}.{key} must be a number")
    return Vec3(x=float(obj["x"]), y=float(obj["y"]), z=float(obj["z"]))


def parse_robot_info(payload: Any) -> RobotInfo:
    obj = _object(payload, "robot")
    robot_id = normalize_robot_id(_string(obj, "robotId", "robot"))
    lifecycle = parse_enum(RobotLifecycle, obj.get("lifecycle"), RobotLifecycle.UNKNOWN)
    dimension = _string(obj, "dimensionId", "robot")
    if "position" not in obj:
        raise ProtocolError("robot.position is required")
    position = parse_vec3(obj["position"], "robot.position")
    commands = obj.get("supportedCommands") or []
    if not isinstance(commands, list) or not all(isinstance(c, str) for c in commands):
        raise ProtocolError("robot.supportedCommands must be an array of strings")
    tick = obj.get("lastUpdatedTick", 0)
    if isinstance(tick, bool) or not isinstance(tick, int):
        raise ProtocolError("robot.lastUpdatedTick must be an integer")
    return RobotInfo(
        robot_id=robot_id,
        lifecycle=lifecycle,
        dimension=dimension,
        position=position,
        supported_commands=tuple(commands),
        last_updated_tick=tick,
        raw=obj,
    )


def parse_command_result(payload: Any) -> CommandResult:
    obj = _object(payload, "commandResult")
    state = parse_enum(CommandState, obj.get("state"), CommandState.UNKNOWN)
    if state is CommandState.UNKNOWN:
        raise ProtocolError(f"commandResult.state is missing or unknown: {obj.get('state')!r}")
    return CommandResult(
        robot_id=normalize_robot_id(_string(obj, "robotId", "commandResult")),
        correlation_id=_optional_string(obj, "correlationId") or "",
        internal_command_id=_optional_string(obj, "internalCommandId"),
        state=state,
        error=_optional_string(obj, "error"),
        message=_optional_string(obj, "message") or "",
    )


def parse_ack(payload: Any) -> Ack:
    obj = _object(payload, "ack")
    status = parse_enum(AckStatus, obj.get("status"), AckStatus.UNKNOWN)
    if status is AckStatus.UNKNOWN:
        raise ProtocolError(f"ack.status is missing or unknown: {obj.get('status')!r}")
    state_raw = obj.get("state")
    state = None
    if state_raw is not None:
        state = parse_enum(CommandState, state_raw, CommandState.UNKNOWN)
    return Ack(
        robot_id=normalize_robot_id(_string(obj, "robotId", "ack")),
        correlation_id=_optional_string(obj, "correlationId") or "",
        command_type=_optional_string(obj, "type") or CommandType.UNKNOWN.value,
        status=status,
        state=state,
        internal_command_id=_optional_string(obj, "internalCommandId"),
        error=_optional_string(obj, "error"),
        message=_optional_string(obj, "message") or "",
        raw=obj,
    )


def parse_cancel_ack(payload: Any) -> CancelAck:
    obj = _object(payload, "cancelAck")
    raw_status = obj.get("status")
    # UNKNOWN is a legitimate CancelAck status, not a parse fallback.
    if raw_status not in {status.value for status in CancelStatus}:
        raise ProtocolError(f"cancelAck.status is missing or unknown: {raw_status!r}")
    status = CancelStatus(raw_status)
    state_raw = obj.get("state")
    state = None
    if state_raw is not None:
        state = parse_enum(CommandState, state_raw, CommandState.UNKNOWN)
    return CancelAck(
        robot_id=normalize_robot_id(_string(obj, "robotId", "cancelAck")),
        correlation_id=_optional_string(obj, "correlationId") or "",
        status=status,
        state=state,
        error=_optional_string(obj, "error"),
        message=_optional_string(obj, "message") or "",
        raw=obj,
    )


def parse_event(payload: Any) -> RobotEvent:
    obj = _object(payload, "event")
    event_type = parse_enum(EventType, obj.get("type"), EventType.UNKNOWN)
    result = None
    if obj.get("commandResult") is not None:
        result = parse_command_result(obj["commandResult"])
    tick = obj.get("serverTick")
    if isinstance(tick, bool) or not isinstance(tick, int):
        raise ProtocolError("event.serverTick must be an integer")
    return RobotEvent(
        event_id=_string(obj, "eventId", "event"),
        server_epoch=_integer(obj, "serverEpoch", "event"),
        sequence=_integer(obj, "sequence", "event"),
        server_tick=tick,
        type=event_type,
        robot_id=normalize_robot_id(_string(obj, "robotId", "event")),
        correlation_id=_optional_string(obj, "correlationId"),
        command_result=result,
        detail=_optional_string(obj, "detail"),
    )


def parse_batch(payload: Any) -> EventBatch:
    obj = _object(payload, "batch")
    events = [parse_event(item) for item in _array(obj, "events", "batch")]
    return EventBatch(
        server_epoch=_integer(obj, "serverEpoch", "batch"),
        after_sequence=_integer(obj, "afterSequence", "batch"),
        oldest_available_sequence=_integer(obj, "oldestAvailableSequence", "batch"),
        latest_sequence=_integer(obj, "latestSequence", "batch"),
        next_sequence=_integer(obj, "nextSequence", "batch"),
        missed=_boolean(obj, "missed", "batch"),
        events=tuple(events),
    )


def encode_command(command: Any) -> Dict[str, Any]:
    if not hasattr(command, "to_wire"):
        raise TypeError("command must provide to_wire()")
    wire = command.to_wire()
    if not isinstance(wire, dict) or not isinstance(wire.get("type"), str):
        raise TypeError("command.to_wire() must return a dict with a string type")
    return wire
