# API v1 Runtime Validation — State, Velocity Setpoints and Lifecycle

> Validation dates: 2026-10-29 (state/setpoint slice), 2026-11-02 (lifecycle and
> inventory/block observation)
> Implementation under test: `9f8d95e` plus the 2026-11-02 lifecycle and
> observation slices
> Branch: `feature/api-v1-state-motion`
> Result: real integrated-server validation completed for state, setpoints,
> collision, action coexistence, legacy ownership, restart, chunk unload/reload,
> permanent removal, dimension change, nine-slot inventory and explicit block
> observation. One lifecycle defect was found and fixed. Independent yaw/pitch
> comparison and the dimension transition window remain unverified.

## 1. Environment

| Component | Value |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.165.0+26.3 |
| Java | Temurin 25.0.4.1+1 |
| Loom / Gradle | 1.19.3 / 9.8.2 |
| Mod build | `swarmie-0.1.1-SNAPSHOT` from the current worktree |
| Launch | `.\gradlew.bat runClient --offline -PswarmieQuickPlay="New World"` |
| Server | Minecraft integrated server, real server ticks |
| Transport | localhost JSONL, `127.0.0.1:25580` |
| Test transport config | temporary `run/config/swarmie-transport.json` (`enabled=true`); removed after validation to restore the default-off state |
| World | existing disposable `New World`; 15 loaded robots in `minecraft:the_nether` |
| Evidence | `run/logs/latest.log`, SDK responses and measured state samples |

The client reached `Minecraft* 26.3 - Singleplayer` and the Adapter logged:

```text
Adapter session started: epoch=837777350786657310, eventCapacity=1024
Local JSONL transport listening on 127.0.0.1:25580
Adapter contract loaded: v0.1
```

The 2026-10-29 run used the public JSONL/SDK path. GUI chat/command injection
from the DSH session did not reliably reach the game window, so that run did not
spawn, kill or teleport robots. The 2026-11-02 lifecycle run bypassed that input
limitation with the default-off file harness described in §10.1, while all
acceptance observations still used the public JSONL/SDK path.

## 2. Result summary

| # | Scenario | Result | Evidence |
|---|---|---|---|
| 1 | Client + integrated server + transport | **PASS** | log excerpt above; window `Minecraft* 26.3 - Singleplayer` |
| 2 | State uses exact requested UUID | **PASS** | `20277cda…` and `4d1e550b…` read independently; unknown UUID → `RobotNotFound` |
| 3 | Position / dimension / velocity agree with observed server motion | **PASS** | §3.1–§3.4 |
| 4 | Yaw / pitch present and persistent | **PASS (presence)** | nonzero yaw values observed; independent external comparator **UNVERIFIED** |
| 5 | `serverTick` advances while ticking | **PASS** | 5418 → 5451 → … → 8417; 229 after restart |
| 6 | Live state distinct from registry metadata | **PASS** | state has actual velocity/yaw/pitch; repeated reads track movement; `RobotInfo` remains metadata |
| 7 | Unknown UUID | **PASS** | `RobotNotFound` for two absent UUIDs |
| 8 | Setpoint acceptance / replacement / revision | **PASS** | revisions 5→6→7; immediate actual velocity stayed 0 |
| 9 | Acceptance is not immediate attainment | **PASS** | accepted `0.05` and `0.2`; immediate state velocity `0`; actual reached `0.2` after ticks |
| 10 | Velocity clamping | **PASS** | request `1.0` → `clamped=True`, effective `(0.35,0,0)`; actual `0.35` |
| 11 | Vertical collision | **PASS** | descent stopped at `y=49.0`, velocity `-0.16` → `0.0`, position remained fixed |
| 12 | Horizontal collision | **PASS** | +X stopped at `x=67.6`, velocity `0.35` → `0.0`, position remained fixed |
| 13 | Clear / deceleration | **PASS** | target cleared; speed decayed `0.131 → 0.086 → 0.056 → 0.037 → 0.024`; position drifted forward |
| 14 | Discrete action with active setpoint | **PASS** | `MINE_BLOCK` at `(23,48,0)` `SUCCEEDED` while moving at `0.1` |
| 15 | Setpoint not blocked by queued actions | **PASS** | two actions queued, then setpoint accepted (revision 15) before their results |
| 16 | Legacy ownership: setpoint blocks TimedFlight/Stop | **PASS** | both rejected `MOTION_CHANNEL_BUSY` |
| 17 | Legacy ownership: legacy blocks set/clear | **PASS** | both rejected `MOTION_CHANNEL_BUSY` |
| 18 | Clear does not steal legacy ownership | **PASS** | clear rejected while a legacy flight was active |
| 19 | Legacy `TimedFlight` / `Stop` still work | **PASS** | short flight `SUCCEEDED` at tick 691; `Stop` `SUCCEEDED` at tick 693 |
| 20 | Server restart / new epoch | **PASS** | epoch `837777350786657310` → `7257942376109456668`; 15 robots reloaded |
| 21 | Active setpoint does not survive restart | **PASS** | pre-restart target `0.05`; post-restart state velocity `~0` |
| 22 | 3D curve example against real server | **PASS (example behavior)** | exit `0`; repeated state/setpoint updates; final clear accepted |
| 23 | Chunk unload/reload | **PASS — real runtime** | §10.2; `ROBOT_UNLOADED`/`ROBOT_LOADED`; state/control rejected while unloaded |
| 24 | Permanent removal | **PASS — real runtime** | §10.3; registry `REMOVED`; `ROBOT_REMOVED`; queued actions `INTERRUPTED` |
| 25 | Dimension change | **PASS — real runtime** | §10.4; same UUID; unload/dimension-changed/load sequence; actions interrupted |
| 26 | Setpoint clearing across lifecycle | **PASS — real runtime** | §10.2/§10.4; old target does not resume; fixed defect §10.6 |
| 27 | Same-epoch reconnect/cursor | **PASS — real runtime** | §10.5; `resumed=True`, `missed=False`, cursor advances |
| 28 | Independent yaw/pitch equality | **UNVERIFIED** | values read from server entity; no independent external comparator |
| 29 | Temporarily unavailable state | **UNVERIFIED** | not separately reproducible |
| 30 | Dimension transition observation window | **UNVERIFIED** | target was loaded before external sampling; transition window not observed |
| 31 | Inventory snapshot (`slots`, selected slot, freshness) | **PASS — real runtime** | §11; 9 ordered slots, `flint_and_steel` x1, server epoch/tick |
| 32 | Explicit block observation | **PASS — real runtime** | §11; air, andesite, `UNLOADED`, `OUT_OF_BOUNDS`; no fabricated block |
| 33 | Observation read-only/no side effects | **PASS — real runtime** | §11; state/velocity/inventory unchanged after reads |

## 3. State evidence

### 3.1 Exact UUID and snapshot

`20277cda-b6c0-40e3-bce0-c24ee5f0e82d` at the start:

```text
server_tick=5418
position=(0.5, 64.0, 0.5)
velocity=(0, 0, 0)
dimension=minecraft:the_nether
lifecycle=LOADED
```

`4d1e550b-88cd-4cb9-9c13-87468cc458a8` was read independently at
`(0.5, 64.0, 0.5)`. No list-first fallback was used; both were requested by
explicit UUID.

Unknown UUIDs produced:

```text
00000000-0000-4000-8000-000000000000 -> RobotNotFound
11111111-1111-4111-8111-111111111111 -> RobotNotFound
```

### 3.2 Freshness and motion

With `20277…`:

```text
tick 5418  position 0.5,64.0,0.5   velocity 0
tick 5423  position 0.82,64.0,0.5  velocity 0.1
tick 5439  position 2.42,64.0,0.5  velocity 0.1
tick 5451  position 3.62,64.0,0.5  velocity 0.1
```

The state changed with server ticks and actual motion; it was not a repeat of a
static registry row. Other loaded robots had nonzero yaw values (for example
73.12°, -20.28°, 151.56°), demonstrating that orientation is read from live
entities rather than a constant.

### 3.3 Collision

Vertical descent with target `(0, -0.2, 0)`:

```text
tick 6011  y=50.46  velocity.y=-0.16
tick 6015  y=49.66  velocity.y=-0.20
tick 6019  y=49.00  velocity.y=0.0
tick 6103  y=49.00  velocity.y=0.0   (still fixed)
```

Horizontal +X with target `(0.35, 0, 0)`:

```text
tick 7768  x=65.1  velocity.x=0.35
tick 7788  x=67.6  velocity.x=0.0
tick 8029  x=67.6  velocity.x=0.0   (still fixed)
```

The server clipped actual velocity to zero and prevented passage. The test did
not independently query the obstacle block material; the collision evidence is
the actual position/velocity response.

### 3.4 Clear

After a `0.2` target was cleared:

```text
tick 6725  x=5.519  velocity.x=0.131
tick 6729  x=5.925  velocity.x=0.086
tick 6733  x=6.192  velocity.x=0.056
tick 6737  x=6.366  velocity.x=0.037
tick 6741  x=6.481  velocity.x=0.024
```

This matches the documented "release target; controller decelerates" semantics,
not instant braking or position holding.

## 4. Setpoint and interaction evidence

### 4.1 Replacement and acceptance

```text
set .05  -> revision 5, immediate state velocity 0
set .20  -> revision 6, immediate state velocity 0
after 4 server ticks: actual velocity 0.20
clear   -> revision 7, active=false
```

The accepted target did not imply immediate attainment; actual velocity followed
over subsequent ticks.

### 4.2 Clamping

```text
request 1.0 -> accepted=true, clamped=true, effective=(0.35,0,0), revision 13
actual after 0.6 s -> velocity=(0.35,0,0)
```

### 4.3 Action coexistence

While `20277…` had an active `0.1` setpoint:

```text
MINE_BLOCK target (23,48,0)
ack: ACCEPTED QUEUED
result: SUCCEEDED "mined block 23, 48, 0" at tick 6449
state after: position (23.93,49,0.5), velocity 0.08
```

Two actions queued first, then a setpoint was accepted (revision 15) while they
were queued. The actions later failed because their chosen target was already
air, but the setpoint was not serialized behind them. This is evidence about
queue independence, not about the failed targets.

### 4.4 Legacy ownership

With setpoint active:

```text
TimedFlight -> REJECTED MOTION_CHANNEL_BUSY
Stop        -> REJECTED MOTION_CHANNEL_BUSY
```

After clearing, a legacy `TimedFlight` was accepted. While it was active:

```text
set_velocity -> REJECTED MOTION_CHANNEL_BUSY
clear_velocity -> REJECTED MOTION_CHANNEL_BUSY
```

Cancel produced `COMMAND_CANCELLED`; after terminal, `set_velocity` was accepted
again. A real short `TimedFlight(0.05, 10 ticks)` then `Stop` both succeeded and
moved the robot, so legacy behaviour remains functional.

## 5. Restart and lifecycle

Before closing the integrated client, `4d1e…` had an active `0.05` setpoint.
The client was closed with `WM_CLOSE`; the server saved and stopped cleanly.

New session:

```text
old epoch = 837777350786657310
new epoch = 7257942376109456668
4d1e… state after restart:
  position (12.0561, 62.6831, -2.39171)
  velocity ~0
  lifecycle LOADED
robots loaded = 15
```

The active target was not resumed. This is evidence for restart/setpoint
invalidation, but it is **not** evidence that unload, removal or dimension
change clear state; those transitions were not triggered.

## 6. 3D curve example

Executed against the real integrated server:

```text
start (6.7, 64, 0.5)
t=0.6s pos=(7.48,64.73,0.81) speed=0.100 ref_error=2.48
t=1.2s pos=(8.56,65.15,0.62) speed=0.100 ref_error=1.73
t=1.8s pos=(9.30,64.50,-0.12) speed=0.100 ref_error=1.06
t=2.4s pos=(9.32,63.57,-0.87) speed=0.100 ref_error=0.67
velocity cleared: accepted=True active=False
exit=0
```

This demonstrates the public API and cleanup path. It does **not** demonstrate
exact curve following; the example itself states that collisions, acceleration
and speed limits are uncompensated.

## 7. Test layers

### Automated unit tests

```text
.\gradlew.bat test --offline --rerun-tasks
main module: 75 passed, 0 failed
contract module: 12 passed, 0 failed
```

### Protocol/mock tests

```text
python -m unittest discover -s tools\sdk\tests -v
66 passed
```

The in-process mock-server curve example also exited `0`. It remains a mock
result, separate from the real runtime result in §6.

### Audit regression

```text
python -m unittest discover -s tools\audit\tests -v
33 passed

python tools\audit\validate_completeness.py \
  --generated docs\auditing\completeness.generated.json \
  --reviewed docs\auditing\completeness.reviewed.md
errors=0 review_findings=0
```

### Manual/unverified

Independent yaw/pitch comparison, temporarily unavailable state, and the
dimension transition observation window were not completed. The 2026-11-02
lifecycle cases in §10 use the deterministic file harness and the public
JSONL/SDK path; they are not inferred from unit tests.

## 8. Defects and code changes

One production defect was found and fixed on 2026-11-02:

- unloading a robot cleared setpoint storage but did not stop the
  `SwarmieFlightController` desired velocity; the same entity object could
  resume the old target after chunk reload;
- fix: `VelocitySetpoints.forget(UUID, FlightTarget)` now clears setpoint state
  and calls `stopFlight()`; `AdapterSession` uses it on load, unload, removal and
  dimension change;
- regression: `VelocitySetpointsTest.forgetStopsLifecycleMotion` asserts the
  lifecycle reset stops the controller target.

The prior state/setpoint slice had no demonstrated defect.

## 9. Remaining limitations and next step

- The state endpoint is a tick-boundary snapshot, not a stream.
- `clear_velocity()` releases the target and allows deceleration; it is not
  braking-to-zero or position hold.
- Setpoint and legacy motion are mutually exclusive; the documented
  `MOTION_CHANNEL_BUSY` behavior held in both directions.
- The dimension transition window could not be sampled externally before the
  destination loaded; this remains unverified.
- Independent yaw/pitch agreement and temporarily unavailable state remain
  unverified.
- The lifecycle harness is development-only and file-driven; it is not a public
  API and is disabled without its config file.
- No new public API surface is justified by this validation.

Recommended next step: extend the deterministic lifecycle harness with a
transition-window sample (or a second observer client) and repeat the public
JSONL checks for a second dimension pair. Do not expand the API before that
evidence exists.

## 10. Lifecycle validation — 2026-11-02

### 10.1 Trigger harness

The previous GUI input limitation was bypassed with a default-off, file-driven
server harness:

```text
run/config/swarmie-lifecycle-test.json   {"enabled":true}
run/swarmie-lifecycle-test/command.json  request
run/swarmie-lifecycle-test/result.json   result
```

Commands used: `spawn`, `unload`, `reload`, `remove`, `dimension`, `status`,
`cleanup`. The harness:

- acts only on its own spawned robot unless an explicit UUID is supplied to
  `cleanup`;
- triggers unload by moving the **player** to the other dimension; the target
  robot follows Minecraft's native chunk/entity unload path, rather than being
  teleported or registry-edited;
- reloads by moving the player back to the saved dimension/position;
- removes with `Entity.discard()`;
- dimensions with `Entity.teleport(TeleportTransition)` using
  `PLACE_PORTAL_TICKET`, with the destination chunk preloaded;
- restores player position, yaw/pitch, gravity, invulnerability and game mode on
  `reload`/`cleanup`.

The harness is disabled when the config file is absent. Its temporary config was
removed after validation to restore the default-off state. A subsequent launch
logged `Local JSONL transport disabled` and no lifecycle-harness enabled line.
It adds no command or public API.

### 10.2 Scenario A — chunk unload/reload

Target `4b274b95-1f09-4a8f-96a9-6c07da1efb85`, epoch
`3633995883926274801`, overworld.

Before unload:

```text
tick 1449  position (-24.5, 72.0, 1.5)  velocity 0
setpoint accepted revision 1, effective (0.1, 0, 0)
tick ~1462 position (-23.38, 72.0, 1.5) velocity (0.1, 0, 0)
```

After `unload`:

```text
registry lifecycle=UNLOADED
registry dimension=minecraft:overworld
registry position=(-23.28, 72.0, 1.5)
state -> RobotNotLoaded (ROBOT_NOT_LOADED)
set_velocity -> RobotNotLoaded (ROBOT_NOT_LOADED)
clear_velocity -> RobotNotLoaded (ROBOT_NOT_LOADED)
MineBlock -> CommandRejected (ROBOT_NOT_LOADED)
event 37 ROBOT_UNLOADED detail="chunk unload"
```

After `reload`:

```text
event 39 ROBOT_LOADED detail=""
state tick 1470, same UUID, dimension overworld
actual velocity 0.081 -> 0.043 -> 0.023 -> 0.012 -> 0.006 -> 0.003
```

The residual velocity is momentum after the fixed controller stop, not the old
target; it decays without re-acceleration. A new setpoint was accepted at
revision 1, moved the robot, and was cleared.

### 10.3 Scenario B — permanent removal

Target `5c17d867-698c-4602-8608-94b789d84ea2`, epoch
`3633995883926274801`, overworld. An active `0.1` setpoint and six queued
`MINE_BLOCK` actions were present before removal.

```text
remove -> Entity.discard()
registry lifecycle=REMOVED
state -> RobotNotLoaded (ROBOT_REMOVED)
set_velocity -> RobotNotLoaded (ROBOT_REMOVED)
clear_velocity -> RobotNotLoaded (ROBOT_REMOVED)
MineBlock -> CommandRejected (ROBOT_REMOVED)
actions: #0 FAILED (target air before removal), #1..#5 INTERRUPTED
events 78 ROBOT_UNLOADED, 79 ROBOT_REMOVED detail="DISCARDED"
```

No ghost entity remained in the registry; the removed UUID resolves as
`REMOVED`, not `LOADED` or `UNKNOWN`.

### 10.4 Scenario C — dimension change

Target `583ae16d-f18e-47f8-8668-b38b20c06a5e`, epoch
`3633995883926274801`, overworld → nether. A `0.1` setpoint and three queued
actions were active.

```text
events 16 ROBOT_UNLOADED detail="chunk unload"
       17 ROBOT_DIMENSION_CHANGED detail="dimension change"
       18 ROBOT_LOADED detail="dimension change complete"
registry lifecycle=LOADED, dimension=minecraft:the_nether
state same UUID, position (0.5, 64.5, 0.5), velocity 0
actions: #0 FAILED (target air), #1/#2 INTERRUPTED
new setpoint accepted revision 1 after transition
```

The old target did not migrate. The transition window itself was not observed
externally before `ROBOT_LOADED`; that specific window remains UNVERIFIED.

### 10.5 Scenario D — reconnect/cursor

Same server epoch `3633995883926274801`:

```text
before disconnect: last_sequence 79, missed=False, gap=None
spawn event while disconnected -> reconnect:
  SessionInfo(epoch=same, resumed=True, missed=False, gap=None)
  last_sequence 80
remove event while disconnected -> reconnect:
  SessionInfo(epoch=same, resumed=True, missed=False, gap=None)
  last_sequence 82
```

The client cursor advanced across lifecycle events without a gap. No new epoch
was created in this scenario; the 2026-10-29 restart result remains the separate
new-epoch evidence. No side-effect action was replayed to test events.

### 10.6 Defect before/after — reload must not resume the old target

The first unload/reload run (before the fix) had an active `0.1` setpoint. After
reload, the same entity object still had the controller's old desired velocity:

```text
state velocity = (0.081, 0, 0)
position continued moving
new setpoint revision = 1 only after the old target had resumed
```

This violated the contract's “reload must not resume the old target.” The fix
in §8 stops the controller during lifecycle cleanup. After the fix, the same
scenario produced:

```text
state velocity = (0.081, 0, 0) immediately after reload
then 0.043 -> 0.023 -> 0.012 -> 0.006 -> 0.003
new setpoint accepted revision 1
```

The residual velocity is momentum; it decays without re-acceleration and only a
new setpoint produces sustained motion.

## 11. Inventory and block observation — 2026-11-02

Target `95a8cfac-7b4c-4210-b34f-aee91ad6c764`, epoch
`1114624977489722601`, dimension `minecraft:overworld`, position
`(-24.5, 80.0, 3.5)`.

### 11.1 Inventory

```text
state serverTick=302
inventory serverEpoch=1114624977489722601 serverTick=303 selectedSlot=0
slots:
  0 minecraft:flint_and_steel count=1
  1..8 empty
```

`Robot.inventory()` returned exactly nine ordered slots and the selected slot.
Empty slots had no item id and count 0. The snapshot contained no raw Minecraft
stack or item-component map.

### 11.2 Block observation

```text
(-24, 79, 3)   OK, block=minecraft:air, air=true, serverTick=304
(-24, 60, 3)   OK, block=minecraft:andesite, air=false, serverTick=305
(100000, 64, 100000)
               UNLOADED, block=None, reason="chunk is not loaded", serverTick=306
(-24, 1000, 3) OUT_OF_BOUNDS, block=None, serverTick=307
unknown UUID   RobotNotFound
```

`UNLOADED` and `OUT_OF_BOUNDS` were successful observations, not transport
errors, and neither was converted to air. The block response carried the
explicit coordinate, status, block id/properties/air summary when `OK`, reason
otherwise, and server epoch/tick.

### 11.3 Read-only check

Immediately before and after the observation calls:

```text
state position = (-24.5, 80.0, 3.5)
state velocity = (0, 0, 0)
inventory slot 0 = flint_and_steel x1
selectedSlot = 0
```

The observations changed neither position, velocity, inventory nor selected
slot. The server tick advanced from 302 to 316 while the snapshots remained
read-only.

### 11.4 Test layers for this slice

- Java: main 75 passed, contract 12 passed, 0 failed.
- Python SDK/mock: 66 passed.
- Real integrated server: inventory, air, solid block, unloaded chunk,
  out-of-bounds and unknown UUID as above.
- No entity scan, local sensor, orientation setpoint, batch query or new action
  was added.

## 12. Minimal farming workflow — 2026-11-02

A public-SDK farming preparation workflow was exercised against a disposable
integrated-server world. Fixture setup (farmland + sixteen wheat seeds) was
performed by the existing default-off lifecycle harness; the workflow itself
used only `state`, `inventory`, `observe_block` and `submit`.

```text
epoch 4827882152154627687, robot 50c72e67-3317-4a6a-b385-91dc60f1808f
position (-24.5, 72.0, 1.5)

before inventory: tick 756, selectedSlot 0, minecraft:wheat_seeds x16
before farmland: (-23,72,1) OK minecraft:farmland
before crop:     (-23,73,1) OK minecraft:air

ack:      ACCEPTED QUEUED
terminal: SUCCEEDED, sequence 14, serverTick 760
after inventory: tick 760, minecraft:wheat_seeds x15
after farmland:  (-23,72,1) OK minecraft:farmland
after crop:      (-23,73,1) OK minecraft:wheat {age:0}, tick 762
```

The action succeeded, but the farmland block itself was unchanged; the crop
appeared above it. The workflow therefore required the inventory decrement and
the explicit crop observation, not just `SUCCEEDED`.

Failure path with an unloaded target:

```text
target before: UNLOADED, block=None, "chunk is not loaded"
ack:      ACCEPTED QUEUED
terminal: FAILED EXECUTION_FAILED, sequence 18, serverTick 764
target after:  UNLOADED, block=None
inventory: tick 774, wheat_seeds x15
```

This is a terminal action failure after admission, not a request rejection and
not a timeout; it left the inventory and target unchanged.

The runnable example is
[`tools/sdk/examples/06_farm_workflow.py`](../tools/sdk/examples/06_farm_workflow.py),
and the reproducible procedure is
[`docs/farming-workflow.md`](farming-workflow.md). This proves only one
farmland/seed action with post-state confirmation; it does not claim general
farming, harvesting, growth, water management, seed transfer or a new
`place_block` primitive.

## 13. Spatial capability decision — 2026-11-02

The farming workflow was re-measured on a real integrated server with no window
focus, using the existing public path only.

| Operation | p50 | p95 |
|---|---:|---:|
| `state()` | 0.372 ms | 0.576 ms |
| `inventory()` | 0.324 ms | 0.499 ms |
| `observe_block()` | 50.017 ms | 50.699 ms |

Epoch `2553467471069402712`, robot
`0c5a6391-c20d-401b-917e-b4e8fbf1e569`; 30 warm samples. Block reads are
server-thread queued and therefore cost about one server tick. Action
`UseItemOnBlock` was acknowledged in 3.748 ms, reached `SUCCEEDED` in 104.409 ms
(server tick 410), and the post-action crop was visible on tick 417.

Decision: **no new spatial capability is justified** by this workflow.

- Explicit block target/face/hit was sufficient; no robot rotation was needed.
- The target was a block, so entity observation would not help.
- One known-coordinate block read was sufficient to confirm the crop; region or
  local sensing was not needed.
- The failure was an unloaded coordinate, already represented by `UNLOADED`, not
  an alignment failure.
- The earlier “SUCCEEDED but farmland unchanged” observation was a crop-above-
  farmland semantic, not a range/aim problem.

No orientation setpoint, explicit-entity observation, local/region sensor,
pathfinding, target search or range-preflight capability was added. The existing
limitation remains: the caller must already know a loaded, in-range block
coordinate. If a future workflow needs target discovery or facing, that is a
separate capability decision with its own evidence.

See [`docs/farming-workflow.md`](farming-workflow.md) for the reproducible
procedure and raw observations.
