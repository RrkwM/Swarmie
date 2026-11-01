#!/usr/bin/env python3
"""Read-only: list robots, their lifecycle and last-known metadata.

Run from any directory:

    python tools/sdk/examples/01_list_robots.py
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from swarmie import SwarmieClient, SwarmieError  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description="List Swarmie robots (read-only)")
    parser.add_argument("--host", default=os.environ.get("SWARMIE_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("SWARMIE_PORT", "25580")))
    args = parser.parse_args()

    try:
        with SwarmieClient(args.host, args.port) as client:
            print(f"epoch={client.epoch} protocol={client.protocol}")
            robots = client.robots()
            print(f"robots={len(robots)}")
            for robot in robots:
                print(
                    f"  {robot.robot_id}  {robot.lifecycle.value:8s} "
                    f"{robot.dimension}  {robot.position}"
                )
    except SwarmieError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
