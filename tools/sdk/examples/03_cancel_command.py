#!/usr/bin/env python3
"""Start a long flight, cancel it while running, then read the terminal state.

    python tools/sdk/examples/03_cancel_command.py --robot <uuid>
"""
from __future__ import annotations

import argparse
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from swarmie import (  # noqa: E402
    CancelStatus,
    SwarmieClient,
    SwarmieError,
    TimedFlight,
    Vec3,
)


def main() -> int:
    parser = argparse.ArgumentParser(description="Cancel a running command")
    parser.add_argument("--host", default=os.environ.get("SWARMIE_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("SWARMIE_PORT", "25580")))
    parser.add_argument("--robot", required=True, help="exact robot UUID")
    parser.add_argument("--timeout", type=float, default=15.0)
    args = parser.parse_args()

    try:
        with SwarmieClient(args.host, args.port) as client:
            robot = client.robot(args.robot)
            if not robot.status().lifecycle.is_loaded:
                print("robot is not loaded; refusing to submit", file=sys.stderr)
                return 2

            handle = robot.submit(TimedFlight(Vec3(0.1, 0.0, 0.0), 6000))
            print(
                f"submitted correlation={handle.correlation_id} "
                f"ack={handle.ack.status.value}"
            )
            time.sleep(0.5)  # let the Runtime start it
            ack = handle.cancel()
            print(f"cancel status={ack.status.value} state={ack.state} message={ack.message!r}")
            if ack.status is CancelStatus.UNKNOWN:
                return 2
            result = handle.wait(args.timeout)
            print(
                f"terminal state={result.state.value} error={result.error} "
                f"message={result.message!r}"
            )
            return 0 if result.state.value == "CANCELLED" else 1
    except SwarmieError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
