# API v1 Runtime Validation — State and Velocity Setpoints

> Validation date: 2026-10-29
> Implementation under test: `9f8d95e Add API v1 self-state and velocity setpoints`
> Branch: `feature/api-v1-state-motion`
> Result: real integrated-server validation completed for state, setpoints,
> collision, action coexistence, legacy ownership and restart. No production
> defect was demonstrated; no production code was changed by this validation.

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

The test used the public JSONL/SDK path. Minecraft chat/command injection from
the DSH session did not reliably reach the game window, so no commands were used
to spawn, kill or teleport robots. This is an environment/input limitation, not
an API failure; it is why the lifecycle cases in §5 are marked BLOCKED.

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
| 23 | Unloaded / removed / dimension-change lifecycle | **BLOCKED** | no reliable chat/command injection to trigger safely |
| 24 | Independent yaw/pitch equality | **UNVERIFIED** | values read from server entity; no independent external comparator |
| 25 | Temporarily unavailable state | **UNVERIFIED** | not reproducible without lifecycle control |

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
main module: 50 passed, 0 failed
contract module: 9 passed, 0 failed
```

### Protocol/mock tests

```text
python -m unittest discover -s tools\sdk\tests -v
55 passed
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

No unload, removal, dimension-change, temporary-unavailability, or independent
yaw/pitch comparison was completed. The in-game command input limitation
prevented safe triggering of those cases. They remain BLOCKED/UNVERIFIED rather
than inferred from unit tests.

## 8. Defects and code changes

No production defect was demonstrated in this run. No production code, contract,
protocol, SDK behavior or tests were changed in this validation iteration.

No regression test was added because there was no demonstrated defect. The
existing unit/mock tests already cover the implemented semantics.

## 9. Remaining limitations and next step

- The state endpoint is a tick-boundary snapshot, not a stream; this was
  observed and matches the contract.
- `clear_velocity()` releases the target and allows deceleration; it is not
  braking-to-zero or position hold.
- Setpoint and legacy motion are mutually exclusive; the documented
  `MOTION_CHANNEL_BUSY` behavior held in both directions.
- Lifecycle cases (chunk unload, removal, dimension change, temporary
  unavailability) still require a deterministic in-game harness or manual run
  with reliable command input.
- Independent yaw/pitch agreement was not measured.
- No new API surface is justified by this validation.

Recommended next step: add or execute a deterministic lifecycle test procedure
that can spawn/kill/teleport robots via a reliable server-side test harness, then
re-run the same public JSONL path for unload/removal/dimension-change. Do not
expand the API before those lifecycle guarantees are evidenced.
