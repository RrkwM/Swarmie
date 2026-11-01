#!/usr/bin/env python3
"""Explicitly select one UUID, fly briefly, check the result, then stop.

    python tools/sdk/examples/02_fly_and_stop.py --robot <uuid> --duration 20
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from swarmie import Stop, SwarmieClient, SwarmieError, TimedFlight, Vec3  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description="Fly one explicitly selected robot")
    parser.add_argument("--host", default=os.environ.get("SWARMIE_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("SWARMIE_PORT", "25580")))
    parser.add_argument("--robot", required=True, help="exact robot UUID")
    parser.add_argument("--duration", type=int, default=20, help="ticks, 1..6000")
    parser.add_argument("--vx", type=float, default=0.1)
    parser.add_argument("--vy", type=float, default=0.0)
    parser.add_argument("--vz", type=float, default=0.0)
    parser.add_argument("--timeout", type=float, default=15.0)
    args = parser.parse_args()

    try:
        with SwarmieClient(args.host, args.port) as client:
            robot = client.robot(args.robot)  # exact UUID, never "first loaded"
            status = robot.status()
            print(f"robot={robot.robot_id} lifecycle={status.lifecycle.value} "
                  f"dimension={status.dimension}")
            if not status.lifecycle.is_loaded:
                print("robot is not loaded; refusing to submit", file=sys.stderr)
                return 2

            flight = robot.submit(
                TimedFlight(Vec3(args.vx, args.vy, args.vz), args.duration)
            )
            print(
                f"flight correlation={flight.correlation_id} "
                f"ack={flight.ack.status.value} state={flight.state.value}"
            )
            result = flight.wait(args.timeout)
            print(
                f"flight terminal state={result.state.value} error={result.error} "
                f"message={result.message!r} gap={result.had_gap}"
            )
            if not result.ok:
                return 1

            stop = robot.submit(Stop())
            stop_result = stop.wait(args.timeout)
            print(
                f"stop terminal state={stop_result.state.value} "
                f"error={stop_result.error} message={stop_result.message!r}"
            )
            return 0 if stop_result.ok else 1
    except SwarmieError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
