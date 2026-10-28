"""Swarmie Python SDK.

Importing this package requires no third-party dependencies::

    from swarmie import SwarmieClient, TimedFlight, Stop, Vec3

The SDK talks to the optional localhost JSONL transport documented in
``docs/local-transport.md``. That transport is default-off, loopback-only and
has no authentication; it is a development interface, not a remote service.
"""
from __future__ import annotations

from .client import CommandHandle, Robot, SwarmieClient
from .commands import MineBlock, Stop, TimedFlight, UseItemOnBlock
from .errors import (
    CancelRejected,
    CancelUnknown,
    CommandRejected,
    ConnectionClosed,
    ConnectionFailure,
    EventGapError,
    ProtocolError,
    RequestTimeout,
    RobotNotFound,
    ServerError,
    SessionChangedError,
    SwarmieError,
    WaitTimeout,
)
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
    Gap,
    RobotEvent,
    RobotInfo,
    RobotLifecycle,
    SessionInfo,
    Vec3,
    normalize_robot_id,
)

__version__ = "0.1.0"

__all__ = [
    "__version__",
    "SwarmieClient",
    "Robot",
    "CommandHandle",
    "TimedFlight",
    "Stop",
    "MineBlock",
    "UseItemOnBlock",
    "Vec3",
    "Ack",
    "CancelAck",
    "CommandResult",
    "EventBatch",
    "RobotEvent",
    "RobotInfo",
    "SessionInfo",
    "Gap",
    "AckStatus",
    "CancelStatus",
    "CommandState",
    "CommandType",
    "EventType",
    "RobotLifecycle",
    "normalize_robot_id",
    "SwarmieError",
    "ConnectionFailure",
    "ConnectionClosed",
    "ProtocolError",
    "RequestTimeout",
    "ServerError",
    "RobotNotFound",
    "CommandRejected",
    "CancelRejected",
    "CancelUnknown",
    "WaitTimeout",
    "SessionChangedError",
    "EventGapError",
]
