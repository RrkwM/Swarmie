"""Typed command builders for the four commands in Adapter contract 0.1.

There is deliberately no pathfinding, naming, inventory or GUI-container
command here: those capabilities do not exist in the Adapter yet.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any, Dict

from .models import Vec3

MAX_DURATION_TICKS = 6000
FACES = frozenset({"DOWN", "UP", "NORTH", "SOUTH", "EAST", "WEST"})


def _finite(value: float, what: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{what} must be a number")
    value = float(value)
    if not math.isfinite(value):
        raise ValueError(f"{what} must be finite")
    return value


def _integer(value: int, what: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{what} must be an integer")
    return value


@dataclass(frozen=True)
class TimedFlight:
    velocity: Vec3
    duration_ticks: int

    def __post_init__(self) -> None:
        if not isinstance(self.velocity, Vec3):
            raise ValueError("velocity must be a Vec3")
        for axis in (self.velocity.x, self.velocity.y, self.velocity.z):
            _finite(axis, "velocity component")
        if not 1 <= _integer(self.duration_ticks, "duration_ticks") <= MAX_DURATION_TICKS:
            raise ValueError(f"duration_ticks must be in 1..{MAX_DURATION_TICKS}")

    def to_wire(self) -> Dict[str, Any]:
        return {
            "type": "TIMED_FLIGHT",
            "velocity": self.velocity.to_wire(),
            "durationTicks": self.duration_ticks,
        }


@dataclass(frozen=True)
class Stop:
    def to_wire(self) -> Dict[str, Any]:
        return {"type": "STOP"}


@dataclass(frozen=True)
class MineBlock:
    x: int
    y: int
    z: int

    def __post_init__(self) -> None:
        _integer(self.x, "x")
        _integer(self.y, "y")
        _integer(self.z, "z")

    def to_wire(self) -> Dict[str, Any]:
        return {
            "type": "MINE_BLOCK",
            "position": {"x": self.x, "y": self.y, "z": self.z},
        }


@dataclass(frozen=True)
class UseItemOnBlock:
    x: int
    y: int
    z: int
    face: str
    hit: Vec3

    def __post_init__(self) -> None:
        _integer(self.x, "x")
        _integer(self.y, "y")
        _integer(self.z, "z")
        if self.face not in FACES:
            raise ValueError(f"face must be one of {sorted(FACES)}")
        if not isinstance(self.hit, Vec3):
            raise ValueError("hit must be a Vec3")
        for axis in (self.hit.x, self.hit.y, self.hit.z):
            _finite(axis, "hit component")

    def to_wire(self) -> Dict[str, Any]:
        return {
            "type": "USE_ITEM_ON_BLOCK",
            "position": {"x": self.x, "y": self.y, "z": self.z},
            "face": self.face,
            "hit": self.hit.to_wire(),
        }
