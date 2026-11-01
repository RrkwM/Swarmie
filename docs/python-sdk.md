English | [简体中文](python-sdk.zh-CN.md)

# Python SDK

> Status: first version (`0.1.0`), Python 3.9+ standard library only, pre-1.0.
> It speaks the existing localhost JSONL development transport; it is not a
> remote or authenticated interface.

## 1. What this is

The SDK is a thin, typed Python layer over the Adapter's JSONL protocol:

- `SwarmieClient` owns one connection, robot discovery, events and session state;
- `Robot` is bound to **one explicit UUID** and can never silently retarget;
- `CommandHandle` tracks one submitted command from acknowledgement to terminal
  result, including cancel and timeout;
- `RobotInfo`, `RobotState`, `SetpointAck`, `CommandResult`, `EventBatch` and a
  typed exception hierarchy make failures explicit.

Supported commands are exactly the four Adapter commands:

```text
TIMED_FLIGHT
STOP
MINE_BLOCK
USE_ITEM_ON_BLOCK
```

The SDK also exposes live `Robot.state()` for the robot itself and a persistent
velocity setpoint (`Robot.set_velocity()` / `clear_velocity()`). There is no
pathfinding, `MoveTo`, naming, inventory, GUI-container, block/entity/sensor
observation or orientation-setpoint API in this slice, because the Adapter has
no such public operations yet.

## 2. Enable the transport

The transport is **default-off**. Create `config/swarmie-transport.json` in the
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

Restart the world/client. The log should contain:

```text
Local JSONL transport listening on 127.0.0.1:25580
```

The listener is hard-coded to loopback. It has no authentication, TLS or
multi-user security model; never expose it to a network. See
[`local-transport.md`](local-transport.md) for the transport and protocol.

## 3. Import

The package lives at `tools/sdk/swarmie`; there is no `pip` package yet. Either
run scripts from the repository with:

```python
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))  # tools/sdk for examples
from swarmie import SwarmieClient, TimedFlight, Vec3, Stop
```

or add `tools/sdk` to `PYTHONPATH` in VS Code:

```json
{ "python.analysis.extraPaths": ["./tools/sdk"] }
```

The runnable examples handle this path setup themselves.

## 4. Discover and select a robot

```python
from swarmie import SwarmieClient, RobotNotFound

with SwarmieClient("127.0.0.1", 25580) as client:
    print(client.epoch, client.protocol)
    for info in client.robots():
        print(info.robot_id, info.lifecycle.value, info.dimension, info.position)

    robot = client.robot("3f2a...")  # exact UUID; raises RobotNotFound
    print(robot.status().lifecycle.value)
```

`robot(uuid)` queries the registry snapshot and matches the UUID exactly. The
SDK never chooses the first loaded robot. `UNLOADED` and `REMOVED` robots are
returned as lifecycle states; submitting to them is rejected explicitly by the
server (`ROBOT_NOT_LOADED` / `ROBOT_REMOVED`).

`SwarmieClient.from_env()` uses `SWARMIE_HOST` and `SWARMIE_PORT`.
All methods are synchronous; use one client per thread.

## 5. Live self-state and persistent velocity

### 5.1 Read state

`Robot.state()` performs a server-side `state` request. It is not the registry
metadata in `RobotInfo`; it contains the authoritative live position, actual
velocity, yaw/pitch, dimension, lifecycle and the server tick used as freshness.

```python
state = robot.state()
print(state.server_tick, state.position, state.velocity, state.yaw, state.pitch)
if not state.lifecycle.is_loaded:
    ...
```

- `server_epoch`/`server_tick` identify the session and freshness point;
- `state.velocity` is the actual native velocity, not the setpoint target;
- a non-loaded/removed/unavailable robot raises `RobotNotLoaded`; an absent UUID
  raises `RobotNotFound`;
- no inventory, block/region/entity/sensor or environmental completeness query
  is exposed in this slice.

### 5.2 Velocity setpoints

```python
ack = robot.set_velocity(Vec3(0.1, 0.0, 0.0))
print(ack.accepted, ack.active, ack.revision, ack.effective_velocity)

ack = robot.clear_velocity()
print(ack.accepted, ack.active)
```

- A setpoint is a persistent target, **not** a `TimedFlight` action and not in
  the action FIFO.
- A newer accepted setpoint replaces the previous target for velocity; use
  `revision` for ordering.
- `ack.accepted` means the server accepted the target; it does not mean the
  robot has reached it. Read `state().velocity` to observe the response.
- The server clamps velocities longer than `0.35` blocks/tick and reports
  `clamped=True` plus the effective vector.
- `set_velocity(0)` is an explicit zero target, not a position-hold guarantee.
  `clear_velocity()` releases the target and lets the controller decelerate.
- While a velocity setpoint is active, `TimedFlight`/`Stop` submissions are
  rejected with `MOTION_CHANNEL_BUSY`; clear the setpoint first. While a
  `TimedFlight`/`Stop` command is active or queued, setpoint requests are
  rejected with `MOTION_CHANNEL_BUSY`; wait for the legacy motion to finish.
- The low-level debug commands `swarmie velocity`/`swarmie stop` are not public
  SDK operations and bypass this arbitration.
- Only the velocity channel exists in this slice. Orientation, hover and
  position hold are not exposed.

## 6. Submit, wait, inspect

```python
from swarmie import TimedFlight, Vec3

handle = robot.submit(TimedFlight(Vec3(0.1, 0.0, 0.0), 20))
print(handle.ack.status.value, handle.state.value)   # ACCEPTED QUEUED

result = handle.wait(timeout=15.0)                   # terminal only
print(result.state.value, result.error, result.message)
```

`SubmitAck.ACCEPTED` only means the inbound boundary accepted the request.
`handle.state` moves through `QUEUED`/`RUNNING`; only `wait()` returns a
terminal `CommandResult` (`SUCCEEDED`, `FAILED`, `CANCELLED`, `INTERRUPTED`).

`CommandResult` carries:

```text
robot_id, correlation_id, internal_command_id, state, error, message,
event_type, sequence, server_tick, from_ack, had_gap
```

`from_ack=True` means the terminal state came from a duplicate acknowledgement
because no retained terminal event was available; it is a known state, not a
fresh event.

## 7. Cancel

```python
ack = handle.cancel()                 # CancelAck
print(ack.status.value)               # ACCEPTED / ALREADY_TERMINAL / UNKNOWN
result = handle.wait(timeout=10.0)    # COMMAND_CANCELLED event
```

- `ACCEPTED` only queues the cancellation; the terminal event is authoritative.
- `ALREADY_TERMINAL` carries the known state and `wait()` returns it.
- `UNKNOWN` means the correlation is no longer known; `cancel_and_wait()`
  raises `CancelUnknown` instead of pretending success.
- A rejected cancel (for example a correlation owned by another robot) raises
  `CancelRejected`.

## 8. Failures are explicit

| Situation | SDK behaviour |
|---|---|
| Unknown UUID | `RobotNotFound` |
| Robot exists but is not loaded/available | `RobotNotLoaded` for state; `CommandRejected` for actions |
| Submit rejected (unloaded/removed/queue/full/conflict) | `CommandRejected` with the structured ack |
| Setpoint rejected (unsupported channel/motion busy/invalid) | `SetpointRejected` with the structured ack |
| Command did not finish in time | `WaitTimeout` carrying the handle and current state |
| Cancel unknown | `CancelUnknown` (or a `CancelAck` with `status=UNKNOWN`) |
| Socket closed/reset | `ConnectionClosed` |
| No response in time | `RequestTimeout` |
| Protocol-level `error` message | `ServerError` with `code`/`message` |
| Event cursor missed events | `client.missed=True`; `wait()` raises `EventGapError` unless the awaited result is already terminal (then `result.had_gap=True`) |
| Server epoch changed | `SessionChangedError`; pending handles cannot complete |

## 9. Events, reconnect and sessions

`wait()` reads pushed event batches and routes them by correlation id. Events
for other robots are stored, not discarded. `client.poll_events()` performs a
read request using the client cursor.

- `client.last_sequence` advances only over events actually applied;
- `client.missed` and `client.gap` expose lost ranges; the SDK never fabricates
  missing events;
- `client.reconnect()` opens a new connection:
  - same `serverEpoch`: replays from the cursor, then returns
    `SessionInfo(resumed=True)`;
  - different epoch: pending handles are invalidated, the cursor resets, and it
    returns `SessionInfo(resumed=False)`; waiting on old handles raises
    `SessionChangedError`.

A reconnect is a resynchronization, not a guarantee that events published while
disconnected were retained.

## 10. Examples

| Script | Purpose |
|---|---|
| [`01_list_robots.py`](../tools/sdk/examples/01_list_robots.py) | read-only robot list and metadata |
| [`02_fly_and_stop.py`](../tools/sdk/examples/02_fly_and_stop.py) | explicit UUID, short flight, result check, stop |
| [`03_cancel_command.py`](../tools/sdk/examples/03_cancel_command.py) | start a long flight, cancel it, read terminal state |
| [`04_mine_block.py`](../tools/sdk/examples/04_mine_block.py) | optional world-changing mining example |
| [`05_curve_flight.py`](../tools/sdk/examples/05_curve_flight.py) | repeated live-state reads and persistent velocity setpoints on a 3D curve |

Run one from the repository root:

```powershell
python tools/sdk/examples/01_list_robots.py
python tools/sdk/examples/02_fly_and_stop.py --robot <uuid> --duration 20
python tools/sdk/examples/05_curve_flight.py --robot <uuid> --duration 15
```

The end-to-end smoke test remains at
[`tools/client/swarmie_client.py`](../tools/client/swarmie_client.py); it now
uses the SDK and accepts `--robot <uuid>`.

## 11. Tests

```powershell
python -m unittest discover -s tools/sdk/tests -v
```

The suite uses a controllable in-process mock server and covers message
encoding/decoding, request/response correlation, explicit UUID selection,
ack-versus-result, timeouts, cancel and terminal states, duplicates, rejected
submissions, connection close, server errors, same-epoch reconnect, epoch
change, event gaps, per-robot isolation, live-state parsing, setpoint
acceptance/replacement/clear and setpoint rejection. It does **not** replace a
real Minecraft integration test. A separate real integrated-server run is
recorded in [`api-v1-runtime-validation.md`](api-v1-runtime-validation.md).

## 12. Limits

- localhost development transport only; default-off, loopback, no auth/TLS;
- four legacy commands only; the only additional public operations are live
  self-state and the velocity setpoint; no MoveTo, inventory, containers,
  naming, LOS or batch;
- live state is limited to robot self-state; no block/entity/sensor queries;
- velocity setpoints only; no orientation, hover, position hold or trajectory;
- `TimedFlight`/`Stop` and velocity setpoints exclude each other on the motion
  channel (`MOTION_CHANNEL_BUSY`);
- synchronous, single-threaded API; no asyncio/background worker;
- no cross-process persistence: command/event history is per server session;
- no dedicated-server, second-client or multiplayer verification; in-game
  validation is limited to real Minecraft 26.3 integrated-server sessions for
  state/setpoints and lifecycle (see `docs/api-v1-runtime-validation.md`);
- Python SDK version `0.1.0` and Adapter contract `0.1` are pre-stable.
