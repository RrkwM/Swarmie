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

## 7. Spatial capability decision

The real workflow does **not** justify a new spatial capability:

- `use_item_on_block` takes an explicit block position, face and hit point; the
  robot did not need to rotate toward the target. The action succeeded while
  stationary with its existing yaw.
- The target was a block, not an entity; entity observation would not add
  information needed here.
- One `observe_block` at the known farmland/crop coordinate was enough to
  distinguish air from wheat. A region scan or local sensor was not needed.
- The previous failure was an unloaded coordinate, already represented by
  `UNLOADED`; it was not an alignment or range-discovery failure.
- The confusing result was semantic, not spatial: the crop is placed above the
  farmland, so the workflow observes the crop coordinate rather than assuming
  the target block mutated.

Not implemented, and not justified by this workflow: orientation setpoint,
explicit-entity observation, local/region sensing, pathfinding, target search or
range preflight. The existing limitation is that the caller must already know a
loaded, in-range block coordinate; no automatic target discovery is promised.

### Performance measurements

Real integrated server, no window focus, localhost JSONL, epoch
`2553467471069402712`, robot `0c5a6391-c20d-401b-917e-b4e8fbf1e569`.
Thirty warm samples per read on the existing path:

| Operation | p50 | p95 | min / max |
|---|---:|---:|---:|
| `Robot.state()` | 0.372 ms | 0.576 ms | 0.255 / 0.650 ms |
| `Robot.inventory()` | 0.324 ms | 0.499 ms | 0.227 / 1.170 ms |
| `Robot.observe_block()` | 50.017 ms | 50.699 ms | 44.768 / 51.647 ms |

`observe_block` is executed on the server thread, so its latency is effectively
one server tick (about 50 ms at 20 TPS). That is explicit and bounded by the
request timeout, not a reason to add a broader observer.

Action measurement for the same fixture:

```text
ack:      ACCEPTED QUEUED, 3.748 ms, server tick 407
terminal: SUCCEEDED, sequence 7, server tick 410, wait 104.409 ms
after:    inventory tick 416, seed count 15; crop tick 417, wheat
```

The action terminal result arrived after about two server ticks; post-action
inventory/block snapshots followed on the next end tick. These measurements are
for the local development transport and this small fixture, not a production
latency guarantee.

Conclusion: no spatial primitive is added. The correct next work, if any, is a
separate capability decision for target discovery/alignment, not a silent
extension of this workflow.
