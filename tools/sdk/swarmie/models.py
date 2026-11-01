"""Wire-level data models for the Swarmie JSONL Adapter protocol.

The field names mirror the Java contract records; unknown enum values are
mapped to ``UNKNOWN`` so a newer server cannot crash an older client.
"""
from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from enum import Enum
from types import MappingProxyType
from typing import Any, Dict, Mapping, Optional, Tuple


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
class BlockPos:
    x: int
    y: int
    z: int

    def __post_init__(self) -> None:
        for axis in (self.x, self.y, self.z):
            if isinstance(axis, bool) or not isinstance(axis, int):
                raise ValueError("block position components must be integers")

    def to_wire(self) -> Dict[str, int]:
        return {"x": self.x, "y": self.y, "z": self.z}

    def __str__(self) -> str:
        return f"({self.x}, {self.y}, {self.z})"


@dataclass(frozen=True)
class InventorySlot:
    slot: int
    item_id: Optional[str]
    count: int

    def __post_init__(self) -> None:
        if isinstance(self.slot, bool) or not isinstance(self.slot, int) or not 0 <= self.slot < 9:
            raise ValueError("inventory slot must be in 0..8")
        if isinstance(self.count, bool) or not isinstance(self.count, int) or self.count < 0:
            raise ValueError("inventory count must be a non-negative integer")
        if self.item_id is None:
            if self.count != 0:
                raise ValueError("an empty inventory slot must have count 0")
        elif not isinstance(self.item_id, str) or not self.item_id or self.count == 0:
            raise ValueError("a non-empty inventory slot needs an item id and positive count")

    @property
    def empty(self) -> bool:
        return self.item_id is None


@dataclass(frozen=True)
class InventoryState:
    """Stable nine-slot inventory snapshot with server-tick freshness."""

    robot_id: str
    server_epoch: int
    server_tick: int
    selected_slot: int
    slots: Tuple[InventorySlot, ...]

    def __post_init__(self) -> None:
        if self.selected_slot < 0 or self.selected_slot >= 9:
            raise ValueError("selected_slot must be in 0..8")
        if len(self.slots) != 9:
            raise ValueError("inventory must contain exactly 9 slots")
        for index, slot in enumerate(self.slots):
            if slot.slot != index:
                raise ValueError("inventory slots must be ordered 0..8")


class ObservationStatus(str, Enum):
    OK = "OK"
    UNLOADED = "UNLOADED"
    OUT_OF_BOUNDS = "OUT_OF_BOUNDS"
    INVALID = "INVALID"
    UNSUPPORTED = "UNSUPPORTED"
    ERROR = "ERROR"


@dataclass(frozen=True)
class BlockSnapshot:
    position: BlockPos
    block_id: str
    properties: Mapping[str, str]
    air: bool


@dataclass(frozen=True)
class BlockObservation:
    """One explicit block read. Non-OK status never fabricates an air block."""

    robot_id: str
    position: BlockPos
    status: ObservationStatus
    block: Optional[BlockSnapshot]
    reason: str
    server_epoch: int
    server_tick: int


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
class RobotState:
    """Server-authoritative live self-state snapshot.

    ``server_tick`` is the freshness indicator for the whole snapshot; it is
    not derived from the client clock. Registry metadata in :class:`RobotInfo`
    is not a substitute for this snapshot.
    """

    robot_id: str
    server_epoch: int
    server_tick: int
    dimension: str
    position: Vec3
    velocity: Vec3
    yaw: float
    pitch: float
    lifecycle: RobotLifecycle
    raw: Dict[str, Any] = field(default_factory=dict, compare=False, repr=False)


@dataclass(frozen=True)
class SetpointAck:
    """Acknowledgement of a velocity-setpoint request.

    Acceptance means the server accepted the target; it does not mean the
    actual velocity has reached it.
    """

    robot_id: str
    channel: str
    revision: int
    active: bool
    accepted: bool
    effective_velocity: Optional[Vec3]
    clamped: bool
    server_epoch: int
    server_tick: int
    error: Optional[str]
    message: str
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
