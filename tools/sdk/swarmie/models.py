"""Wire-level data models for the Swarmie JSONL Adapter protocol.

The field names mirror the Java contract records; unknown enum values are
mapped to ``UNKNOWN`` so a newer server cannot crash an older client.
"""
from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, Optional, Tuple


class AckStatus(str, Enum):
    ACCEPTED = "ACCEPTED"
    REJECTED = "REJECTED"
    DUPLICATE = "DUPLICATE"
    UNKNOWN = "UNKNOWN"


class CancelStatus(str, Enum):
    ACCEPTED = "ACCEPTED"
    REJECTED = "REJECTED"
    ALREADY_TERMINAL = "ALREADY_TERMINAL"
    UNKNOWN = "UNKNOWN"


class CommandState(str, Enum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"
    INTERRUPTED = "INTERRUPTED"
    UNKNOWN = "UNKNOWN"

    @property
    def is_terminal(self) -> bool:
        return self in {
            CommandState.SUCCEEDED,
            CommandState.FAILED,
            CommandState.CANCELLED,
            CommandState.INTERRUPTED,
        }


class RobotLifecycle(str, Enum):
    LOADED = "LOADED"
    UNLOADED = "UNLOADED"
    REMOVED = "REMOVED"
    UNKNOWN = "UNKNOWN"

    @property
    def is_loaded(self) -> bool:
        return self is RobotLifecycle.LOADED


class EventType(str, Enum):
    ROBOT_LOADED = "ROBOT_LOADED"
    ROBOT_UNLOADED = "ROBOT_UNLOADED"
    ROBOT_DIMENSION_CHANGED = "ROBOT_DIMENSION_CHANGED"
    ROBOT_REMOVED = "ROBOT_REMOVED"
    COMMAND_ENQUEUED = "COMMAND_ENQUEUED"
    COMMAND_ACCEPTED = "COMMAND_ACCEPTED"
    COMMAND_STARTED = "COMMAND_STARTED"
    COMMAND_COMPLETED = "COMMAND_COMPLETED"
    COMMAND_FAILED = "COMMAND_FAILED"
    COMMAND_CANCELLED = "COMMAND_CANCELLED"
    COMMAND_INTERRUPTED = "COMMAND_INTERRUPTED"
    UNKNOWN = "UNKNOWN"


class CommandType(str, Enum):
    TIMED_FLIGHT = "TIMED_FLIGHT"
    STOP = "STOP"
    MINE_BLOCK = "MINE_BLOCK"
    USE_ITEM_ON_BLOCK = "USE_ITEM_ON_BLOCK"
    UNKNOWN = "UNKNOWN"


def parse_enum(enum_type, value, fallback):
    """Return ``enum_type(value)`` or ``fallback`` for unknown values."""
    try:
        return enum_type(value)
    except (TypeError, ValueError):
        return fallback


@dataclass(frozen=True)
class Vec3:
    x: float
    y: float
    z: float

    def to_wire(self) -> Dict[str, float]:
        return {"x": float(self.x), "y": float(self.y), "z": float(self.z)}

    def __str__(self) -> str:
        return f"({self.x:g}, {self.y:g}, {self.z:g})"


@dataclass(frozen=True)
class RobotInfo:
    """Last-known registry metadata for one robot."""

    robot_id: str
    lifecycle: RobotLifecycle
    dimension: str
    position: Vec3
    supported_commands: Tuple[str, ...]
    last_updated_tick: int
    raw: Dict[str, Any] = field(default_factory=dict, compare=False, repr=False)


@dataclass(frozen=True)
class Ack:
    """SubmitAck: acceptance is not completion."""

    robot_id: str
    correlation_id: str
    command_type: str
    status: AckStatus
    state: Optional[CommandState]
    internal_command_id: Optional[str]
    error: Optional[str]
    message: str
    raw: Dict[str, Any] = field(default_factory=dict, compare=False, repr=False)

    @property
    def accepted(self) -> bool:
        return self.status in {AckStatus.ACCEPTED, AckStatus.DUPLICATE}


@dataclass(frozen=True)
class CancelAck:
    robot_id: str
    correlation_id: str
    status: CancelStatus
    state: Optional[CommandState]
    error: Optional[str]
    message: str
    raw: Dict[str, Any] = field(default_factory=dict, compare=False, repr=False)


@dataclass(frozen=True)
class CommandResult:
    """Authoritative command outcome (or an ack-derived duplicate shortcut)."""

    robot_id: str
    correlation_id: str
    internal_command_id: Optional[str]
    state: CommandState
    error: Optional[str]
    message: str
    event_type: Optional[str] = None
    sequence: Optional[int] = None
    server_tick: Optional[int] = None
    from_ack: bool = False
    had_gap: bool = False

    @property
    def ok(self) -> bool:
        return self.state is CommandState.SUCCEEDED


@dataclass(frozen=True)
class RobotEvent:
    event_id: str
    server_epoch: int
    sequence: int
    server_tick: int
    type: EventType
    robot_id: str
    correlation_id: Optional[str]
    command_result: Optional[CommandResult]
    detail: Optional[str]


@dataclass(frozen=True)
class EventBatch:
    server_epoch: int
    after_sequence: int
    oldest_available_sequence: int
    latest_sequence: int
    next_sequence: int
    missed: bool
    events: Tuple[RobotEvent, ...]

    @property
    def empty(self) -> bool:
        return not self.events


@dataclass(frozen=True)
class Gap:
    """Half-open description of a lost event range."""

    after_sequence: int
    oldest_available_sequence: int

    def __str__(self) -> str:
        return (
            f"gap after {self.after_sequence}, "
            f"oldest available {self.oldest_available_sequence}"
        )


@dataclass(frozen=True)
class SessionInfo:
    epoch: int
    protocol: str
    resumed: bool
    missed: bool
    gap: Optional[Gap]


def normalize_robot_id(value: str) -> str:
    """Return the canonical lowercase UUID string, or raise ValueError."""
    if not isinstance(value, str):
        raise ValueError(f"robot UUID must be a string, got {type(value).__name__}")
    try:
        return str(uuid.UUID(value))
    except (ValueError, AttributeError) as exc:
        raise ValueError(f"invalid robot UUID: {value!r}") from exc


def validate_correlation(value: str) -> str:
    if not isinstance(value, str) or not 1 <= len(value) <= 128:
        raise ValueError("correlation id must be a string of 1..128 characters")
    return value
