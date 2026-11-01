"""Exception hierarchy for the Swarmie Python SDK.

Every failure the SDK can observe is mapped to one of these types; none of them
is silently converted into a successful result.
"""
from __future__ import annotations


class SwarmieError(Exception):
    """Base class for all SDK errors."""


class ConnectionFailure(SwarmieError):
    """The TCP connection could not be established."""


class ConnectionClosed(SwarmieError):
    """The server closed the connection (or it was reset)."""


class ProtocolError(SwarmieError):
    """A message did not match the documented JSONL protocol."""


class RequestTimeout(SwarmieError):
    """No response arrived before the request deadline."""


class ServerError(SwarmieError):
    """The server answered with a protocol-level ``error`` message."""

    def __init__(self, code: str, message: str = "", request_id=None) -> None:
        self.code = code
        self.message = message
        self.request_id = request_id
        super().__init__(f"server error {code}: {message}")

    def __str__(self) -> str:
        return f"server error {self.code}: {self.message}"


class RobotNotFound(SwarmieError):
    """No robot with that exact UUID exists in the current registry snapshot."""

    def __init__(self, robot_id: str) -> None:
        self.robot_id = robot_id
        super().__init__(f"robot not found: {robot_id}")


class RobotNotLoaded(SwarmieError):
    """The robot exists but is not currently available for live state/control."""

    def __init__(self, robot_id: str, code: str = "ROBOT_NOT_LOADED") -> None:
        self.robot_id = robot_id
        self.code = code
        super().__init__(f"robot not loaded: {robot_id} ({code})")


class SetpointRejected(SwarmieError):
    """The server rejected a setpoint request without changing the target."""

    def __init__(self, ack) -> None:
        self.ack = ack
        super().__init__(
            f"setpoint rejected: {ack.error or 'unknown error'} ({ack.message})"
        )


class CommandRejected(SwarmieError):
    """The Adapter rejected a submission; the command was not accepted."""

    def __init__(self, ack) -> None:
        self.ack = ack
        super().__init__(
            f"command rejected: {ack.error or 'unknown error'} ({ack.message})"
        )


class CancelRejected(SwarmieError):
    """The Adapter rejected a cancellation request."""

    def __init__(self, ack) -> None:
        self.ack = ack
        super().__init__(
            f"cancel rejected: {ack.error or 'unknown error'} ({ack.message})"
        )


class CancelUnknown(SwarmieError):
    """The correlation is unknown to the server; nothing can be cancelled."""

    def __init__(self, ack) -> None:
        self.ack = ack
        super().__init__(f"cancel unknown: {ack.message}")


class WaitTimeout(SwarmieError):
    """A command did not reach a terminal state before the deadline."""

    def __init__(self, handle, timeout: float) -> None:
        self.handle = handle
        self.timeout = timeout
        super().__init__(
            f"command {handle.correlation_id} did not finish within {timeout}s "
            f"(state={handle.state.value})"
        )


class SessionChangedError(SwarmieError):
    """The server session (serverEpoch) changed and old state is invalid."""

    def __init__(self, old_epoch, new_epoch) -> None:
        self.old_epoch = old_epoch
        self.new_epoch = new_epoch
        super().__init__(
            f"server session changed: epoch {old_epoch} -> {new_epoch}; "
            "old commands cannot be resumed"
        )


class EventGapError(SwarmieError):
    """The event cursor missed events; the SDK will not pretend to fill them."""

    def __init__(self, after_sequence: int, oldest_available: int) -> None:
        self.after_sequence = after_sequence
        self.oldest_available = oldest_available
        super().__init__(
            "event gap: events after sequence "
            f"{after_sequence} are no longer retained (oldest available "
            f"{oldest_available}); reconnect/resync instead of assuming delivery"
        )
