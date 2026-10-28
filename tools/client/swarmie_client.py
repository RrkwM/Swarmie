#!/usr/bin/env python3
"""End-to-end smoke test for the localhost JSONL transport, built on the SDK.

The transport is a development aid only: default-off, binds 127.0.0.1 and has
no authentication. This script still needs a running Minecraft client/server
with the transport enabled.

    python tools/client/swarmie_client.py --robot <uuid>

Without ``--robot`` the smoke test uses the first LOADED robot *for this test
scenario only* and prints that choice; the SDK itself never defaults to it.
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "sdk"))

from swarmie import (  # noqa: E402
    CancelStatus,
    MineBlock,
    Stop,
    SwarmieClient,
    SwarmieError,
    TimedFlight,
    Vec3,
)


def main() -> int:
    parser = argparse.ArgumentParser(description="Swarmie localhost JSONL smoke test")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25580)
    parser.add_argument("--robot", help="exact robot UUID")
    parser.add_argument("--duration", type=int, default=20)
    parser.add_argument("--mine-x", type=int, default=500)
    parser.add_argument("--mine-y", type=int, default=99)
    parser.add_argument("--mine-z", type=int, default=500)
    args = parser.parse_args()

    client = SwarmieClient(args.host, args.port)
    run = str(int(time.time()))
    mine_corr = "py-mine-" + run
    fly_corr = "py-fly-" + run
    stop_corr = "py-stop-" + run
    cancel_corr = "py-cancel-" + run

    try:
        robots = client.robots()
        print(f"ROBOTS epoch={client.epoch} count={len(robots)}")
        for info in robots:
            print(f"  {info.robot_id} {info.lifecycle.value} {info.dimension}")

        if args.robot:
            robot = client.robot(args.robot)
        else:
            loaded = [info for info in robots if info.lifecycle.is_loaded]
            if not loaded:
                print("FAIL no loaded robots", file=sys.stderr)
                return 1
            print(f"NOTE no --robot supplied; smoke test selects {loaded[0].robot_id}")
            robot = client.robot(loaded[0].robot_id)
        print(f"TARGET {robot.robot_id} lifecycle={robot.lifecycle.value}")

        mine = robot.submit(MineBlock(args.mine_x, args.mine_y, args.mine_z), mine_corr)
        try:
            mine_result = mine.wait(5.0)
            print(f"MINE {mine_result.state.value} {mine_result.error} {mine_result.message!r}")
        except SwarmieError as exc:
            print(f"MINE no terminal result: {exc}")

        flight = robot.submit(
            TimedFlight(Vec3(0.1, 0.0, 0.0), args.duration), fly_corr
        )
        flight_result = flight.wait(20.0)
        print(f"FLY {flight_result.state.value} {flight_result.error} {flight_result.message!r}")

        stop = robot.submit(Stop(), stop_corr)
        stop_result = stop.wait(10.0)
        print(f"STOP {stop_result.state.value} {stop_result.error} {stop_result.message!r}")

        duplicate = robot.submit(Stop(), stop_corr)
        print(f"DUPLICATE ack={duplicate.ack.status.value} state={duplicate.state.value}")

        long_flight = robot.submit(
            TimedFlight(Vec3(0.0, 0.1, 0.0), 6000), cancel_corr
        )
        time.sleep(0.5)
        cancel_ack = long_flight.cancel()
        print(f"CANCEL ack={cancel_ack.status.value} state={cancel_ack.state}")
        if cancel_ack.status is not CancelStatus.UNKNOWN:
            cancelled = long_flight.wait(10.0)
            print(f"CANCEL terminal={cancelled.state.value} {cancelled.message!r}")
        client.close()

        # A fresh connection: report the server session and cursor state.
        with SwarmieClient(args.host, args.port) as reconnect:
            batch = reconnect.poll_events(max_events=1)
            print(
                f"RECONNECT epoch={reconnect.epoch} latest={batch.latest_sequence} "
                f"missed={batch.missed}"
            )
        return 0 if flight_result.ok and stop_result.ok else 1
    except SwarmieError as exc:
        print(f"ERROR {exc}", file=sys.stderr)
        return 1
    finally:
        client.close()


if __name__ == "__main__":
    sys.exit(main())
