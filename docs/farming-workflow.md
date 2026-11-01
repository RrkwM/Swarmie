# Minimal Farming Workflow

> Scope: one loaded Swarmie, one existing farmland block, one selected seed stack,
> one `USE_ITEM_ON_BLOCK` action, then public inventory/block confirmation.
> This is **not** general farming, harvesting, seed acquisition, water management
> or pathfinding.

## 1. Preconditions

- Minecraft 26.3 integrated server with the Swarmie Adapter and JSONL transport
  enabled;
- a robot selected by exact UUID and currently `LOADED`;
- selected inventory slot containing the expected seed item, for example
  `minecraft:wheat_seeds`;
- a `minecraft:farmland` block within 6 blocks of the robot, with air above it.

`use_item_on_block` is sufficient for this workflow. No `place_block`, aim
raycast, seed transfer, harvest or growth primitive is needed or added.

## 2. Reproducible fixture for a disposable test world

The existing development-only lifecycle harness can create only the fixture:
place farmland at an explicit coordinate, clear the block above it, and put
sixteen wheat seeds in slot 0. It does **not** plant; planting goes through the
public SDK action.

```text
run/config/swarmie-lifecycle-test.json  {"enabled":true}
run/swarmie-lifecycle-test/command.json {"id":"fixture","action":"setup_farm"}
```

The fixture is not a public API and is disabled without the config file. The
production workflow still requires the normal loaded robot and selected item.

## 3. Run the example

```powershell
python tools/sdk/examples/06_farm_workflow.py `
  --robot <uuid> `
  --farmland=-23,72,1
```

The script:

1. reads `Robot.state()`;
2. reads the selected inventory slot and verifies the seed ID/count;
3. observes the farmland and the block above it;
4. submits `UseItemOnBlock(farmland, face="UP", hit=top center)`;
5. prints the ack and waits for the terminal result;
6. re-reads inventory and the **block above the farmland**;
7. passes only if the seed count decreased by one and the block above changed
   from air to `minecraft:wheat`.

## 4. Real integrated-server evidence

Run of 2026-11-02, epoch `4827882152154627687`, robot
`50c72e67-3317-4a6a-b385-91dc60f1808f`, position
`(-24.5, 72.0, 1.5)`:

```text
before inventory: tick=756 selectedSlot=0
  slot 0: minecraft:wheat_seeds x16
before farmland: (-23,72,1) OK minecraft:farmland
before crop:     (-23,73,1) OK minecraft:air

ack:      ACCEPTED QUEUED
terminal: SUCCEEDED, sequence 14, serverTick 760
after inventory: tick=760 slot 0 minecraft:wheat_seeds x15
after farmland:  (-23,72,1) OK minecraft:farmland
after crop:      (-23,73,1) OK minecraft:wheat {age:0}, tick 762
```

The farmland block itself did **not** become the crop. The seed was planted in
the block above it. This is why the workflow does not treat action
`SUCCEEDED` as proof of planting; it checks inventory and the crop coordinate.

The example was rerun from a reset fixture and printed:

```text
PASS: one seed consumed and wheat observed above the farmland
```

## 5. Failure path

Using the same robot and an explicit unloaded target at
`(100000,64,100000)`:

```text
failure target before: UNLOADED, block=None, "chunk is not loaded"
ack:      ACCEPTED QUEUED
terminal: FAILED EXECUTION_FAILED, sequence 18, serverTick 764
failure target after:  UNLOADED, block=None
failure inventory: tick=774, wheat_seeds x15
```

The action was admitted at the boundary but failed at execution validation; no
block was fabricated, no seed was consumed, and the target remained `UNLOADED`.
This is a terminal action failure, not a request rejection and not a timeout.

## 6. What is actually proven

- A Python SDK client selected a robot by UUID and used the public JSONL path.
- One `USE_ITEM_ON_BLOCK` action was accepted and reached a terminal result.
- The selected seed stack changed by exactly one.
- The block above the farmland changed from air to wheat at age 0.
- The unchanged farmland at the target confirms that the planted block was
  above it, not the target itself.
- An unloaded target produced an explicit `FAILED` terminal action with no
  mutation.

Not claimed:

- harvesting, drops, growth, water/hydration or crop state over time;
- seed acquisition or inventory transfer;
- general farming, pathfinding, area scanning or container use;
- `place_block` or any new action/primitive.
