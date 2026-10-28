#!/usr/bin/env python3
"""Fly one explicitly selected robot along a 3D Lissajous-like curve.

This is a *client-side* demonstration, not a trajectory primitive:

* each tick of the loop reads live server state;
* the next velocity setpoint is computed from a reference curve plus a small
  proportional position correction;
* the setpoint is persistent, so it remains in effect between updates;
* the command is not a queued action and does not wait behind mining/use;
* on exit (including Ctrl+C) the velocity target is cleared.

It does not claim exact curve following. Collisions, speed limits, acceleration
limits and tracking error all affect the result.

    python tools/sdk/examples/05_curve_flight.py --robot <uuid>
"""
from __future__ import annotations

import argparse
import math
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from swarmie import (  # noqa: E402
    RobotLifecycle,
    RobotNotLoaded,
    SetpointRejected,
    SwarmieClient,
    SwarmieError,
    Vec3,
)


def curve(t: float, origin: Vec3, amplitude: Vec3, omega: float) -> tuple[Vec3, Vec3]:
    """Reference position and its derivative at time ``t`` (seconds)."""
    phase = omega * t
    position = Vec3(
        origin.x + amplitude.x * math.sin(phase),
        origin.y + amplitude.y * math.sin(2.0 * phase),
        origin.z + amplitude.z * math.cos(phase),
    )
    velocity = Vec3(
        amplitude.x * omega * math.cos(phase),
        amplitude.y * 2.0 * omega * math.cos(2.0 * phase),
        -amplitude.z * omega * math.sin(phase),
    )
    return position, velocity


def add(a: Vec3, b: Vec3) -> Vec3:
    return Vec3(a.x + b.x, a.y + b.y, a.z + b.z)


def scaled(v: Vec3, factor: float) -> Vec3:
    return Vec3(v.x * factor, v.y * factor, v.z * factor)


def clamp_speed(v: Vec3, max_speed: float) -> Vec3:
    speed = math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
    if speed <= max_speed or speed < 1.0e-9:
        return v
    return scaled(v, max_speed / speed)


def main() -> int:
    parser = argparse.ArgumentParser(description="Fly a 3D curve with persistent velocity setpoints")
    parser.add_argument("--host", default=os.environ.get("SWARMIE_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("SWARMIE_PORT", "25580")))
    parser.add_argument("--robot", required=True, help="exact robot UUID")
    parser.add_argument("--duration", type=float, default=15.0, help="seconds")
    parser.add_argument("--rate", type=float, default=10.0, help="control updates per second")
    parser.add_argument("--omega", type=float, default=0.8, help="curve angular speed")
    parser.add_argument("--ax", type=float, default=3.0)
    parser.add_argument("--ay", type=float, default=1.5)
    parser.add_argument("--az", type=float, default=3.0)
    parser.add_argument("--gain", type=float, default=0.5, help="position correction gain")
    parser.add_argument("--max-speed", type=float, default=0.20, help="client clamp; server max is 0.35")
    parser.add_argument("--print-every", type=float, default=1.0, help="seconds between reports")
    args = parser.parse_args()

    if args.duration <= 0 or args.rate <= 0:
        print("duration and rate must be positive", file=sys.stderr)
        return 2
    if not 0 < args.max_speed <= 0.35:
        print("max-speed must be in (0, 0.35]", file=sys.stderr)
        return 2

    try:
        with SwarmieClient(args.host, args.port) as client:
            robot = client.robot(args.robot)  # exact UUID, never "first loaded"
            state = robot.state()
            if state.lifecycle is not RobotLifecycle.LOADED:
                print(f"robot is not loaded: {state.lifecycle.value}", file=sys.stderr)
                return 2

            origin = state.position
            amplitude = Vec3(args.ax, args.ay, args.az)
            print(
                f"robot={robot.robot_id} start={state.position} "
                f"velocity={state.velocity} server_tick={state.server_tick}"
            )
            print(
                "tracking is approximate: client-side reference + proportional correction; "
                "collisions, acceleration and speed limits are not compensated"
            )

            started = time.monotonic()
            last_report = started
            interval = 1.0 / args.rate
            try:
                while True:
                    now = time.monotonic()
                    elapsed = now - started
                    if elapsed >= args.duration:
                        break

                    state = robot.state()
                    if state.lifecycle is not RobotLifecycle.LOADED:
                        print(f"robot became {state.lifecycle.value}; stopping", file=sys.stderr)
                        return 2

                    reference, feed_forward = curve(elapsed, origin, amplitude, args.omega)
                    error = Vec3(
                        reference.x - state.position.x,
                        reference.y - state.position.y,
                        reference.z - state.position.z,
                    )
                    target = clamp_speed(
                        add(feed_forward, scaled(error, args.gain)),
                        args.max_speed,
                    )
                    ack = robot.set_velocity(target)
                    if not ack.accepted:
                        print(f"setpoint rejected: {ack.error} {ack.message}", file=sys.stderr)
                        return 1

                    if now - last_report >= args.print_every:
                        distance = math.sqrt(error.x**2 + error.y**2 + error.z**2)
                        actual_speed = math.sqrt(
                            state.velocity.x**2 + state.velocity.y**2 + state.velocity.z**2
                        )
                        print(
                            f"t={elapsed:5.1f}s pos=({state.position.x:7.2f},"
                            f"{state.position.y:6.2f},{state.position.z:7.2f}) "
                            f"speed={actual_speed:5.3f} ref_error={distance:5.2f} "
                            f"target=({target.x:5.2f},{target.y:5.2f},{target.z:5.2f})"
                        )
                        last_report = now

                    sleep_for = interval - (time.monotonic() - now)
                    if sleep_for > 0:
                        time.sleep(sleep_for)
            except KeyboardInterrupt:
                print("interrupted; clearing velocity target", file=sys.stderr)
                return 130
            finally:
                try:
                    cleared = robot.clear_velocity()
                    print(f"velocity cleared: accepted={cleared.accepted} active={cleared.active}")
                except SwarmieError as exc:
                    print(f"could not clear velocity target: {exc}", file=sys.stderr)
                    return 1
    except RobotNotLoaded as exc:
        print(f"robot unavailable: {exc}", file=sys.stderr)
        return 2
    except SetpointRejected as exc:
        print(f"setpoint rejected: {exc}", file=sys.stderr)
        return 1
    except SwarmieError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    print("curve run finished")
    return 0


if __name__ == "__main__":
    sys.exit(main())
