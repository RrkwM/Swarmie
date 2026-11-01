#!/usr/bin/env python3
"""Optional, world-changing: mine one explicitly selected block.

This is intentionally not the first example. It requires coordinates within the
robot's 6-block interaction range and a loaded target chunk.

    python tools/sdk/examples/04_mine_block.py --robot <uuid> --x 100 --y 64 --z 100
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from swarmie import MineBlock, SwarmieClient, SwarmieError  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description="Mine a block with one robot")
    parser.add_argument("--host", default=os.environ.get("SWARMIE_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("SWARMIE_PORT", "25580")))
    parser.add_argument("--robot", required=True, help="exact robot UUID")
    parser.add_argument("--x", type=int, required=True)
    parser.add_argument("--y", type=int, required=True)
    parser.add_argument("--z", type=int, required=True)
    parser.add_argument("--timeout", type=float, default=15.0)
    args = parser.parse_args()

    try:
        with SwarmieClient(args.host, args.port) as client:
            robot = client.robot(args.robot)
            if not robot.status().lifecycle.is_loaded:
                print("robot is not loaded; refusing to submit", file=sys.stderr)
                return 2
            handle = robot.submit(MineBlock(args.x, args.y, args.z))
            print(f"submitted correlation={handle.correlation_id} ack={handle.ack.status.value}")
            result = handle.wait(args.timeout)
            print(
                f"terminal state={result.state.value} error={result.error} "
                f"message={result.message!r}"
            )
            return 0 if result.ok else 1
    except SwarmieError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
