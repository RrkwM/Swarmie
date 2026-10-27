English | [简体中文](adapter-command-specs.zh-CN.md)

# Adapter Command Specifications (0.1)

> Reflects current `SwarmieRuntime`, `InteractionActor` and `AdapterSession`
> behavior. API success is not evidence of a confirmed world side effect.

## 1. Submission

```java
SwarmieAdapter.submit(robotId, commandSpec, correlationId)
```

- inbox accepted: `ACCEPTED`, state `QUEUED`, internal ID may be null;
- duplicate correlation: `DUPLICATE` with known ID/state;
- conflict: `CORRELATION_CONFLICT`;
- full inbox: `INBOX_FULL`;
- unknown/unloaded/removed robot: `ROBOT_NOT_FOUND`/`ROBOT_NOT_LOADED`/
  `ROBOT_REMOVED`.

Event order:

```text
COMMAND_ENQUEUED
COMMAND_ACCEPTED
COMMAND_STARTED
COMMAND_COMPLETED/FAILED/CANCELLED/INTERRUPTED
```

## 2. TimedFlight

| Item | Specification |
|---|---|
| Parameters | finite `Vec3Dto velocity`; `durationTicks` 1..6000 |
| Preconditions | loaded robot, Runtime not terminated; no destination/chunk validation |
| Queue | per-robot FIFO, max 16 pending |
| Start | next Runtime tick calls `setDesiredVelocity` |
| Cost | duration ticks, then `stopFlight()` until speed ≤ 0.01 |
| Success | stopped below threshold |
| Failure | expected/exception failure → `FAILED` |
| Cancel | queued: no execution; active: stop, movement already made cannot roll back |
| Side effects | velocity/position via native Entity.move and collision |
| Interrupt | unload/removal → `INTERRUPTED`, no resume |
| Idempotency | same correlation does not re-execute; new correlation retries |
| Timeout | duration bound only; no extra watchdog |
| Events | accepted/started/completed/failed/cancelled/interrupted |

No MoveTo/pathfinding/obstacle avoidance is implemented.

## 3. Stop

- no parameters;
- `stopFlight()`;
- success when speed ≤ 0.01 on the next tick;
- queued cancel does nothing; active cancel stops and becomes `CANCELLED`;
- unload/removal → `INTERRUPTED`;
- no hard timeout.

## 4. MineBlock

- parameter: absolute `BlockPosDto`;
- preconditions: loaded robot; target chunk loaded; world border; range ≤ 6;
- execution: native `FakePlayer.gameMode.destroyBlock` on start;
- success: native `true`, command succeeds next tick;
- failure: air/protected/unloaded/out-of-range/native false → `FAILED`;
- side effects: block removed; drops remain in the world; **not auto-collected**;
- tool durability is native;
- cancel before start only; synchronous destruction cannot roll back;
- interruption before start → `INTERRUPTED`.

## 5. UseItemOnBlock

- parameters: `BlockPosDto`, `DirectionDto face`, `Vec3Dto hit`;
- preconditions: loaded robot; target chunk; border; range ≤ 6; valid face/hit;
- execution: native `FakePlayer.gameMode.useItemOn` with the selected slot;
- success: native result other than `PASS`; next tick `SUCCEEDED`;
- failure: air/unloaded/range/PASS → `FAILED`;
- side effects depend on item/block (placement, consumption, ignition, lever);
- containers: **GUI does not open**; FakePlayer `openMenu` is a no-op; chest can
  report success without content changes;
- cancellation after synchronous start cannot roll back;
- retry uses a new correlation.

Container GUI support is a known limitation. The server-side
`MenuProvider.createMenu + AbstractContainerMenu.clicked` path is investigated
but not implemented or live-tested.

## 6. Cancel

- public `SwarmieAdapter.cancel(robotId, correlationId)` returns `CancelAck`;
- unknown: `UNKNOWN` + `UNKNOWN_CORRELATION`;
- terminal: `ALREADY_TERMINAL` + known state;
- queued: `ACCEPTED`; removed from inbox/Runtime and `COMMAND_CANCELLED`;
- active TimedFlight: stop; movement cannot roll back;
- active Mine/Use: cancellation only before start;
- cancellation is asynchronous; terminal event is authoritative;
- cancel queue limit 64; full → `BACKPRESSURE`.

## 7. Exceptions, timeouts, retries

- Runtime catches command exceptions → `FAILED`;
- listener exceptions are isolated;
- unclassified exception → `INTERNAL_ERROR`;
- no general timeout beyond TimedFlight duration; `TIMEOUT` is defined but unused;
- no automatic retry; a retry uses a new correlation;
- unload/removal → `INTERRUPTED`; reload does not resume;
- full Runtime queue → terminal `FAILED/QUEUE_FULL`;
- full global inbox → `INBOX_FULL`, no Runtime entry.

## 8. Evidence

Automated: FIFO, active/queued cancellation, history, unload interruption,
concurrency idempotency, batch limits, event order/overflow.

Real client: flight, stop, query, sensor, iron-pickaxe mining, air failure,
stone placement + inventory consumption, Adapter submit/duplicate/conflict,
Python transport, lifecycle.

No unexecuted client behavior is claimed as passing. See
[`test-matrix.md`](test-matrix.md).
