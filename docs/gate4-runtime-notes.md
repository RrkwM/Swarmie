# Gate 4 — Runtime and command execution notes

> Scope: source implementation + pure-logic tests. Gate 1 (Loom build environment)
> remains blocked, so **no in-game runtime validation has been performed**.

## 1. Ownership and lifecycle

- `SwarmieEntity` owns one `private final SwarmieRuntime runtime`, created in its
  constructor. There is no global map, broker or shared queue.
- `SwarmieEntity.tick()` calls `runtime.tick()` exactly once, and only when
  `!level().isClientSide()`. It then performs the normal flight update and the
  single native `move(MoverType.SELF, ...)`.
- `SwarmieEntity` implements the narrow `FlightTarget` interface; the Runtime
  never touches `Level`, blocks, or the entity's position directly.
- On permanent removal (`onRemoval` with a reason other than
  `UNLOADED_TO_CHUNK` / `UNLOADED_WITH_PLAYER`), active and queued commands become
  `INTERRUPTED` and the Runtime terminates. Chunk unload is not treated as
  destruction.
- A reloaded/recreated entity gets a **fresh** Runtime. Command queues and
  running commands are **not persisted** across restart/reload; this is
  intentional for Gate 4.

## 2. Public API

`SwarmieRuntime`:

| Method | Behaviour |
|---|---|
| `submitTimedFlight(Vec3 velocity, int durationTicks)` | Returns `SubmissionResult`; validates finite velocity and `1..6000` ticks |
| `submitStop()` | Returns `SubmissionResult` |
| `getCommand(long id)` | Active, queued or retained historical command |
| `getCurrentCommand()` | `Optional<SwarmieCommand>` |
| `getQueuedCommands()` | Immutable snapshot |
| `getHistory()` | Immutable snapshot, oldest first |
| `cancel(long id)` | Active or specific queued command |
| `cancelActive()` | Active command only |
| `cancelAll()` | Active + queued; returns number cancelled |

`SubmissionResult` distinguishes acceptance (`accepted`, `commandId`) from
rejection (`accepted=false`, `error`). Rejected submissions never enter the
queue.

## 3. Command state machine

`SwarmieCommand.State`:

```
QUEUED -> RUNNING -> SUCCEEDED
                   -> FAILED
                   -> CANCELLED
                   -> INTERRUPTED
QUEUED -> CANCELLED
QUEUED -> INTERRUPTED
```

- Terminal states are final; a second transition throws `IllegalStateException`.
- Each command records: id, type (`TIMED_FLIGHT`/`STOP`), state, submitted tick,
  start tick, completion tick, and a short result/error string.
- IDs are monotonic per Runtime and are never reused.
- Acceptance into the queue does **not** mean success; only `SUCCEEDED` does.

## 4. Timed flight and stop semantics

**Timed flight**

1. On start, `target.setDesiredVelocity(velocity)` (entity/flight-controller
   clamps speed).
2. The target stays active for `durationTicks` server ticks.
3. On expiry, `target.stopFlight()`.
4. The command remains `RUNNING` until the native velocity length is
   `<= 0.01` blocks/tick, then `SUCCEEDED`.

Duration is validated as `1..6000` ticks. A wall/ceiling does not fail the
command: native collision zeroes blocked axes; the command does not pathfind,
detour or automatically fail solely because of an obstacle.

**Stop**

1. On start, `target.stopFlight()`.
2. Remains `RUNNING` until native speed `<= 0.01`.
3. Then `SUCCEEDED`.

The physical velocity is never zeroed directly by the Runtime; deceleration is
the flight controller's job.

## 5. Cancellation and failures

- **Active cancellation**: calls `target.stopFlight()` for controlled
  deceleration, marks the command `CANCELLED` immediately, retains it, and clears
  the active slot. A queued command may start on a later tick.
- **Queued cancellation by ID**: removes it from the queue, marks `CANCELLED`;
  it can never start later.
- **Cancel all**: active + all queued; returns the number cancelled.
- **Exception during start/tick**: the command becomes `FAILED` with
  `ExceptionClass: message` in its result; the full stack trace is logged via
  SLF4J. The Runtime remains usable and can start later commands.
- A cancelled active command does not wait for deceleration to complete; the
  safe stop continues independently.

## 6. Limits and rationale

| Constant | Value | Rationale |
|---|---|---|
| `MAX_QUEUE_SIZE` | 16 | small bounded FIFO; prevents unbounded submissions |
| `MAX_HISTORY_SIZE` | 32 | enough recent diagnostics without growth |
| `MAX_DURATION_TICKS` | 6000 | 5 minutes at 20 TPS; rejects absurd commands |
| `STOP_SPEED_THRESHOLD` | 0.01 blocks/tick | below visible motion; reachable by damping |

Completed records are evicted oldest-first. An evicted ID is no longer
queryable; IDs are still not reused.

## 7. Debug commands

All require `Commands.LEVEL_GAMEMASTERS` (operator level 2). `<targets>` is an
entity selector; non-Swarmie entities are skipped.

| Command | Behaviour |
|---|---|
| `/swarmie fly <targets> <x> <y> <z> <duration>` | Queue a timed-flight Runtime command per selected robot |
| `/swarmie stop <targets>` | Queue a stop Runtime command |
| `/swarmie status <target>` | Current command, queued count, retained count |
| `/swarmie results <target>` | Last up to 5 retained results |
| `/swarmie cancel <targets>` | Cancel active command |
| `/swarmie cancelid <targets> <id>` | Cancel a specific active/queued ID |
| `/swarmie cancelall <targets>` | Cancel active + queued |
| `/swarmie velocity <targets> <x> <y> <z>` | **Low-level convenience**: direct `setDesiredVelocity`, no Runtime record |

The low-level `velocity` command is deliberately distinguished from queued
Runtime commands; it does not create a command ID or completion state.

## 8. Static validation

Production + client compilation:

```text
javac @tools/provision-tmp/diagnostics/javac.args
```

Result: **exit 0**.

Pure-logic tests (`RuntimeProbe`) use the real `SwarmieRuntime`,
`SwarmieFlightController` and command classes; only `FlightTarget` is faked as a
simple velocity carrier:

```text
javac @tools/provision-tmp/diagnostics/runtime-probe.args   -> 0
java -cp ... RuntimeProbe                                  -> 0
RUNTIME_PROBE_OK
```

Covered:

- valid state transitions / terminal finality (cancel after terminal returns false)
- FIFO ordering and single active command
- timed-flight completion after deceleration below threshold
- stop-command completion
- active cancellation, queued cancellation, cancel-all
- queue-full rejection
- bounded history
- exception-to-`FAILED` and Runtime continuing afterward
- independence of two Runtime instances and per-Runtime IDs
- removal (`isRemoved`) -> `INTERRUPTED` + terminated Runtime
- invalid/NaN/zero/oversized submissions rejected

The exception test intentionally logs a stack trace (`IllegalStateException:
boom`) from the fake target; this is expected test output, not a production
failure.

## 9. Not verified / limitations

- No Loom build, resource processing, packaging or in-game commands.
- No actual server tick / entity removal / chunk unload test.
- Collision-axis behavior of `Entity.move` is still only API/bytecode verified.
- Command queue/history are not persisted across reload/restart.
- `status`/`results` use a single-entity selector.
- No separate client sync for command state (debug commands are server-side
  only; no requirement for client display yet).

## 10. Live-test procedure (after Gate 1)

1. `/summon swarmie:swarmie`
2. `/swarmie fly @e[type=swarmie:swarmie,limit=1] 0.2 0 0 40`
3. `/swarmie status @e[type=swarmie:swarmie,limit=1]`
4. `/swarmie results @e[type=swarmie:swarmie,limit=1]`
5. `/swarmie cancel @e[type=swarmie:swarmie,limit=1]`; verify controlled deceleration.
6. Queue several flights to verify FIFO/one-active; use `cancelid`/`cancelall`.
7. Kill/remove the entity while a command runs; verify no further mutation and
   the Runtime terminates.
