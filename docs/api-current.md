# Current Python / JSONL API — Quick Reference

> **Status: current implementation, pre-stable.** This is a usage reference, not
> the frozen v1 contract. It records what the code on `main` exposes today and
> separates current behavior from planned or experimental work.
>
> Full design and evidence:
> [`api-v1-contract.md`](api-v1-contract.md),
> [`api-v1-runtime-validation.md`](api-v1-runtime-validation.md),
> [`local-transport.md`](local-transport.md),
> [`python-sdk.md`](python-sdk.md).

## 1. What is stable enough to use

| Area | Status | Surface |
|---|---|---|
| Exact robot selection | **Current** | `client.robots()`, `client.robot(uuid)` |
| Live self-state | **Current** | `Robot.state()` / `client.state(uuid)` |
| Inventory snapshot | **Current** | `Robot.inventory()` |
| Single-block observation | **Current** | `Robot.observe_block(BlockPos)` |
| Persistent velocity setpoint | **Current** | `Robot.set_velocity()` / `clear_velocity()` |
| Four legacy actions | **Current** | `submit()` with `TimedFlight`, `Stop`, `MineBlock`, `UseItemOnBlock` |
| Action lifecycle | **Current** | `CommandHandle.ack`, `.wait()`, `.cancel()` |
| Events / reconnect | **Current** | `poll_events()`, `reconnect()` |
| Orientation setpoint | **Not implemented** | — |
| Public region/entity/sensor observation | **Not implemented** | — |
| Typed action methods (`Robot.mine_block`, ...) | **Not implemented** | use generic `submit()` |
| Capability object / per-robot version | **Partial** | only `RobotInfo.supported_commands` |
| Batch over JSONL/SDK | **Not implemented** | Java batch DTO only |
| DCPU/MMIO | **Experimental, test-only** | no public Python/JSONL operation |

“Current” still means pre-1.0: behavior may change before v1.

The in-game `/swarmie help` command documents the OP-level debug command tree
(`/swarmie help flight`, `actions`, `inventory`, `query`, `adapter`,
`lifecycle`). It is not part of the public Python/JSONL API.

## 2. Enable the transport

The localhost JSONL transport is default-off and loopback-only. Create in the
Minecraft run directory:

```json
{
  "enabled": true,
  "port": 25580,
  "maxConnections": 4,
  "maxLineBytes": 8192,
  "maxOutboundEvents": 256
}
```

File: `config/swarmie-transport.json`. Restart the world. The log should contain:

```text
Local JSONL transport listening on 127.0.0.1:25580
```

It has no authentication, TLS or remote binding. Do not expose it.

## 3. Connect and select an exact robot

```python
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))  # tools/sdk
from swarmie import SwarmieClient

with SwarmieClient() as client:
    print(client.epoch, client.protocol)
    for info in client.robots():
        print(info.robot_id, info.lifecycle.value, info.dimension, info.position)

    robot = client.robot("11111111-1111-4111-8111-111111111111")
    print(robot.info)
```

- UUID is canonical and lowercase.
- `robot(uuid)` raises `RobotNotFound`; it never falls back to the first robot.
- `RobotInfo` is last-known registry metadata; `last_updated_tick` exposes its
  freshness.
- `Robot.status()` is a compatibility metadata refresh, not live state.
- `SwarmieClient.from_env()` reads `SWARMIE_HOST` / `SWARMIE_PORT`.

## 4. Live self-state

```python
state = robot.state()
print(state.server_epoch, state.server_tick)
print(state.dimension, state.position, state.velocity)
print(state.yaw, state.pitch, state.lifecycle.value)
```

`server_tick` is the snapshot freshness point. `velocity` is actual native
velocity, not the setpoint target. `RobotInfo.position` is metadata and may be
one or more ticks older.

| Situation | Result |
|---|---|
| unknown UUID | `RobotNotFound` |
| robot not loaded | `RobotNotLoaded` (`ROBOT_NOT_LOADED`) |
| robot removed | `RobotNotLoaded` (`ROBOT_REMOVED`) |
| loaded but no snapshot yet | `RobotNotLoaded` (`ROBOT_UNAVAILABLE`) |

## 5. Inventory

```python
inventory = robot.inventory()
print(inventory.server_epoch, inventory.server_tick, inventory.selected_slot)
for slot in inventory.slots:          # exactly 0..8, in order
    if not slot.empty:
        print(slot.slot, slot.item_id, slot.count)
```

- Nine ordered slots; `selected_slot` is 0..8.
- Empty slot: `item_id is None`, `count == 0`.
- No item components, durability or NBT are exposed.
- The snapshot is refreshed at the server tick boundary; it is read-only.
- Unloaded/removed robots raise `RobotNotLoaded`.

## 6. Single-block observation

```python
from swarmie import BlockPos, ObservationStatus

result = robot.observe_block(BlockPos(10, 64, -3))
print(result.server_epoch, result.server_tick, result.status)
if result.status is ObservationStatus.OK:
    print(result.block.block_id, result.block.properties, result.block.air)
else:
    print(result.reason)
```

| Status | Meaning |
|---|---|
| `OK` | block snapshot is present |
| `UNLOADED` | target chunk is not loaded; no block is present |
| `OUT_OF_BOUNDS` | outside build/world bounds; no block |
| `INVALID` | target cannot be observed |
| `UNSUPPORTED` | observation capability disabled |
| `ERROR` | observation failed |

`UNLOADED`/`OUT_OF_BOUNDS` are explicit results, **not air**. Only `OK` with
`block.air == True` means air.

## 7. Persistent velocity setpoint

```python
from swarmie import Vec3

ack = robot.set_velocity(Vec3(0.1, 0.0, 0.0))
print(ack.accepted, ack.active, ack.revision, ack.effective_velocity)

ack = robot.clear_velocity()
print(ack.accepted, ack.active)
```

- Latest accepted target wins; `revision` disambiguates order.
- The server clamps above `0.35` blocks/tick and sets `clamped=True`.
- Acceptance does not mean achievement; read `state().velocity`.
- `clear_velocity()` releases the target and lets the controller decelerate. It
  is not a position hold.
- The setpoint is **not** in the action FIFO.
- Velocity setpoint and legacy `TimedFlight`/`Stop` are mutually exclusive and
  reject the loser with `MOTION_CHANNEL_BUSY`.
- Only the velocity channel exists; orientation is not implemented.

## 8. Actions

```python
from swarmie import MineBlock, Stop, TimedFlight, UseItemOnBlock, Vec3

handle = robot.submit(TimedFlight(Vec3(0.1, 0, 0), 20), "flight-1")
print(handle.ack.status.value, handle.ack.state.value)

result = handle.wait(timeout=5.0)
print(result.state.value, result.error, result.message)
```

Supported legacy commands:

```text
TIMED_FLIGHT
STOP
MINE_BLOCK
USE_ITEM_ON_BLOCK
```

Semantics:

- per-robot FIFO, one active action at a time;
- `SubmitAck.ACCEPTED` means admitted/queued, not completed;
- terminal result is `SUCCEEDED` / `FAILED` / `CANCELLED` / `INTERRUPTED`;
- `wait()` raises `WaitTimeout` if no terminal state arrives;
- `cancel()` returns a `CancelAck`; the terminal event is authoritative;
- duplicate correlation returns the existing result/handle; conflicting
  correlation is rejected;
- unload/removal/dimension change interrupts queued/active work.

`MINE_BLOCK` and `USE_ITEM_ON_BLOCK` use the selected inventory slot and native
FakePlayer interaction. A native success is not by itself proof of a desired
world side effect. Use `inventory()` and `observe_block()` afterward.

## 9. Events and reconnect

```python
batch = client.poll_events(max_events=64)
for event in batch.events:
    print(event.sequence, event.type.value, event.robot_id, event.detail)

info = client.reconnect()
print(info.epoch, info.resumed, info.missed, info.gap)
```

- Events carry `server_epoch`, `sequence`, `server_tick`, type, robot and
  optional correlation/result.
- Terminal action events are authoritative for asynchronous completion.
- Same-epoch reconnect resumes from the cursor if retained; an older cursor
  produces `missed=True`.
- A different epoch invalidates old cursors and pending handles.
- Events do not survive a server restart.

## 10. Lifecycle

| Lifecycle | Effect |
|---|---|
| `LOADED` | state, inventory, actions and setpoints available |
| `UNLOADED` | metadata retained; live state/actions unavailable; setpoints cleared |
| `REMOVED` | permanent to the client until a new entity with the same UUID appears |
| dimension change | same UUID; unload/reload sequence; setpoints and in-flight actions do not survive |

Reload does **not** restore a previous velocity setpoint.

## 11. Errors

| Error | Meaning |
|---|---|
| `RobotNotFound` | no matching UUID in the registry |
| `RobotNotLoaded` | robot exists but is unloaded/removed/unavailable |
| `CommandRejected` | submission rejected; inspect `ack.error` |
| `SetpointRejected` | setpoint rejected; inspect `ack.error` |
| `CancelRejected` / `CancelUnknown` | cancel request was not accepted/known |
| `WaitTimeout` | no terminal result within the SDK deadline |
| `RequestTimeout` | no transport response within the request deadline |
| `ServerError` | structured JSONL error (for example malformed request) |
| `SessionChangedError` | server epoch changed |
| `EventGapError` | required event history was lost |

`MOTION_CHANNEL_BUSY` means the other motion owner is active. There is no
automatic retry or plan change.

## 12. Minimal workflow

```python
from swarmie import BlockPos, SwarmieClient, Vec3

with SwarmieClient() as client:
    robot = client.robot("11111111-1111-4111-8111-111111111111")
    print(robot.state().position)
    print(robot.inventory().selected_slot)
    print(robot.observe_block(BlockPos(10, 64, -3)).status)
    robot.set_velocity(Vec3(0.05, 0, 0))
    # observe actual motion through state(); clear when done
    robot.clear_velocity()
```

For one farmland/seed preparation action with post-state confirmation, see
[`farming-workflow.md`](farming-workflow.md).

## 13. Explicitly not available

- general farming, harvesting, growth, water management or task scheduling;
- pathfinding, obstacle avoidance, target search or automatic recovery;
- orientation setpoint, position hold or exact hover;
- public region/entity/sensor observation methods;
- inventory transfer, pickup, container GUI or item components;
- typed `Robot.mine_block()` / `Robot.use_item_on_block()` methods;
- JSONL/SDK batch submission;
- remote/public network, authentication or TLS;
- DCPU cartridges, persistence, assembler/debugger, NIC or Lua compatibility;
- a complete DCPU-16 emulator. The DCPU/MMIO path is a test-only subset spike;
  see [`dcpu-mmio-bridge-experiment.md`](dcpu-mmio-bridge-experiment.md).

## 14. Source and evidence

- design/status: [`api-v1-contract.md`](api-v1-contract.md)
- real runtime: [`api-v1-runtime-validation.md`](api-v1-runtime-validation.md)
- transport: [`local-transport.md`](local-transport.md)
- SDK details: [`python-sdk.md`](python-sdk.md)
- tests: [`test-matrix.md`](test-matrix.md)
- farming example: [`farming-workflow.md`](farming-workflow.md)
- DCPU experiment: [`dcpu-mmio-bridge-experiment.md`](dcpu-mmio-bridge-experiment.md)
