#!/usr/bin/env python3
"""Read-only-first farming preparation check using the public SDK.

This example assumes the world already has:
- a loaded Swarmie selected by UUID;
- wheat seeds in the selected slot;
- a farmland block at the given coordinate, with air above it.

It does not create farmland, give items, harvest, transfer items, or claim
general farming support. It submits one USE_ITEM_ON_BLOCK, then verifies the
result through public inventory/block observations.
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

SDK_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SDK_ROOT))

from swarmie import (  # noqa: E402
    BlockPos,
    ObservationStatus,
    SwarmieClient,
    UseItemOnBlock,
    Vec3,
)


def parse_pos(value: str) -> BlockPos:
    parts = value.split(",")
    if len(parts) != 3:
        raise argparse.ArgumentTypeError("position must be x,y,z")
    try:
        return BlockPos(*(int(part) for part in parts))
    except ValueError as exc:
        raise argparse.ArgumentTypeError(str(exc)) from exc


def selected_item(robot):
    inventory = robot.inventory()
    selected = inventory.slots[inventory.selected_slot]
    return inventory, selected


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--robot", required=True, help="exact robot UUID")
    parser.add_argument("--farmland", required=True, type=parse_pos, help="x,y,z")
    parser.add_argument("--seed", default="minecraft:wheat_seeds")
    args = parser.parse_args()

    with SwarmieClient() as client:
        robot = client.robot(args.robot)
        state = robot.state()
        inventory, selected = selected_item(robot)
        before_count = selected.count
        print(
            f"robot={robot.robot_id} epoch={state.server_epoch} "
            f"tick={state.server_tick} selectedSlot={inventory.selected_slot}"
        )
        print(
            f"before inventory: slot={selected.slot} item={selected.item_id} "
            f"count={selected.count}"
        )
        if selected.item_id != args.seed or selected.count < 1:
            print(f"FAIL: selected slot does not contain {args.seed}")
            return 2

        farmland = robot.observe_block(args.farmland)
        print(
            f"before farmland: status={farmland.status.value} "
            f"block={None if farmland.block is None else farmland.block.block_id}"
        )
        if farmland.status is not ObservationStatus.OK:
            print(f"FAIL: farmland is not observable: {farmland.reason}")
            return 2
        if farmland.block is None or farmland.block.block_id != "minecraft:farmland":
            print("FAIL: target is not farmland")
            return 2

        crop_pos = BlockPos(
            args.farmland.x, args.farmland.y + 1, args.farmland.z
        )
        before_crop = robot.observe_block(crop_pos)
        print(
            f"before crop: status={before_crop.status.value} "
            f"block={None if before_crop.block is None else before_crop.block.block_id}"
        )

        hit = Vec3(
            args.farmland.x + 0.5,
            args.farmland.y + 1.0,
            args.farmland.z + 0.5,
        )
        handle = robot.submit(
            UseItemOnBlock(
                args.farmland.x,
                args.farmland.y,
                args.farmland.z,
                "UP",
                hit,
            ),
            "farm-workflow",
        )
        print(
            f"ack: {handle.ack.status.value} "
            f"state={handle.ack.state.value if handle.ack.state else None}"
        )
        result = handle.wait(timeout=5.0)
        print(
            f"terminal: {result.state.value} error={result.error} "
            f"sequence={result.sequence} serverTick={result.server_tick}"
        )
        if not result.ok:
            print("FAIL: action did not succeed; not claiming a planted crop")
            return 1

        after_inventory, after_selected = selected_item(robot)
        deadline = time.monotonic() + 5.0
        while time.monotonic() < deadline:
            if after_inventory.server_tick > inventory.server_tick:
                break
            time.sleep(0.2)
            after_inventory, after_selected = selected_item(robot)

        after_crop = robot.observe_block(crop_pos)
        print(
            f"after inventory: tick={after_inventory.server_tick} "
            f"item={after_selected.item_id} count={after_selected.count}"
        )
        print(
            f"after crop: status={after_crop.status.value} "
            f"block={None if after_crop.block is None else after_crop.block.block_id} "
            f"tick={after_crop.server_tick}"
        )

        if (
            after_selected.item_id != args.seed
            or after_selected.count != before_count - 1
        ):
            print("FAIL: seed count did not decrease by exactly one")
            return 1
        if after_crop.status is not ObservationStatus.OK or after_crop.block is None:
            print("FAIL: crop block was not observable after the action")
            return 1
        if after_crop.block.air or after_crop.block.block_id == "minecraft:farmland":
            print("FAIL: post-action block is still farmland/air")
            return 1
        if after_crop.block.block_id != "minecraft:wheat":
            print(f"FAIL: unexpected crop block {after_crop.block.block_id}")
            return 1

        print("PASS: one seed consumed and wheat observed above the farmland")
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
