English | [简体中文](adapter-contract.zh-CN.md)

# Adapter Contract 0.1

> Status: pre-stable `0.x`. The contract may change during Adapter development.

## 1. Boundary

`:contract` is an independent pure Java Gradle subproject:

- no Minecraft, Fabric, Loom or game types on its compile classpath;
- no dependency on the main mod;
- only records/enums/collections/UUID/IDs.

Forbidden in the contract package:

```text
net.minecraft.*
net.fabricmc.*
```

Compile isolation is enforced by `:contract:compileJava`; `java-library` is the
only production plugin.

## 2. Versioning

```text
ContractVersion.CURRENT = 0.1
```

- major mismatch: reject;
- peer minor newer than local: reject;
- unknown fields: ignore;
- missing required fields: reject;
- unknown command/ordinary enum: reject;
- unknown event type: may map to `UNKNOWN`, never execute.

Wire compatibility is not promised for `0.x`.

## 3. Commands

`CommandType`:

```text
TIMED_FLIGHT
STOP
MINE_BLOCK
USE_ITEM_ON_BLOCK
```

`CommandSpec` is a sealed interface:

| type | fields |
|---|---|
| `TIMED_FLIGHT` | `Vec3Dto velocity`, `int durationTicks` |
| `STOP` | none |
| `MINE_BLOCK` | `BlockPosDto position` |
| `USE_ITEM_ON_BLOCK` | `BlockPosDto position`, `DirectionDto face`, `Vec3Dto hit` |

Constraints:

- `durationTicks`: `1..6000`;
- vectors must be finite;
- block coordinates use integer `BlockPosDto`;
- `face` is one of six directions.

Debug commands `velocity`, `inventory`, `query` and `sensor` are **not** public
contract commands and do not produce Adapter command events.

### 3.1 Live self-state and velocity setpoints

`RobotState` is a server-authoritative snapshot, distinct from the registry
metadata in `RobotDescriptor`:

```text
RobotId robotId
long serverEpoch
long serverTick          // freshness point
String dimensionId
Vec3Dto position
Vec3Dto velocity         // actual native velocity
double yaw, pitch
RobotLifecycleState lifecycle
```

`state` JSONL requests return this snapshot for a loaded robot. Unknown,
unloaded or removed robots return `ROBOT_NOT_FOUND`/`ROBOT_NOT_LOADED`/
`ROBOT_REMOVED`; an unavailable snapshot returns `ROBOT_UNAVAILABLE`.

The velocity setpoint is not a `CommandSpec` and never enters the action FIFO:

```text
SetpointAck(RobotId robotId, String channel, long revision, boolean active,
            boolean accepted, Vec3Dto effectiveVelocity, boolean clamped,
            ErrorCode error, String message, long serverEpoch, long serverTick)
```

`accepted=true` means the target was accepted, not reached. `clear` releases
the target. A setpoint and legacy `TIMED_FLIGHT`/`STOP` are mutually exclusive
on the motion channel; the losing request receives `MOTION_CHANNEL_BUSY`.
Velocities above `ContractLimits.MAX_SETPOINT_SPEED` are clamped and report
`clamped=true`.


## 4. Coordinates and identifiers

- `RobotId`: stable UUID;
- `BlockPosDto(int x, int y, int z)`: absolute block position;
- `Vec3Dto(double x, double y, double z)`: entity position, velocity or hit;
- `DirectionDto`: `DOWN/UP/NORTH/SOUTH/EAST/WEST`;
- resource IDs are namespaced strings.

## 5. Submission

`CommandSubmission`:

```text
RobotId robotId
String correlationId       // 1..128, caller-owned
CommandSpec command
```

`SubmitAck`:

```text
RobotId robotId
String correlationId
CommandType type
AckStatus status           // ACCEPTED / REJECTED / DUPLICATE
String internalCommandId?
CommandState state?
ErrorCode error?
String message
```

`ACCEPTED` means the inbound boundary accepted the request, not that Runtime
executed it. `DUPLICATE` returns the known internal ID/state. Batch:

```text
BatchSubmission(List<CommandSubmission>)   // 1..64
BatchSubmitAck(List<SubmitAck>)            // per item, not atomic
```

Cancel:

```text
RobotId robotId
String correlationId
CancelStatus status       // ACCEPTED/REJECTED/ALREADY_TERMINAL/UNKNOWN
CommandState state?
ErrorCode error?
String message
```

`CancelAck.ACCEPTED` only means the cancellation is queued; the terminal event
is `COMMAND_CANCELLED`.

## 6. Results and state

`CommandState`: `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`,
`INTERRUPTED`.

`CommandResult`:

```text
RobotId robotId
String correlationId
String internalCommandId?
CommandState state
ErrorCode error?
String message
```

The four current commands have no query/sensor payload.

## 7. Events

`RobotEvent`:

```text
String eventId
long serverEpoch
long sequence
long serverTick
RobotEventType type
RobotId robotId
String correlationId?
CommandResult commandResult?
String detail?
```

Types:

```text
ROBOT_LOADED
ROBOT_UNLOADED
ROBOT_DIMENSION_CHANGED
ROBOT_REMOVED
COMMAND_ENQUEUED
COMMAND_ACCEPTED
COMMAND_STARTED
COMMAND_COMPLETED
COMMAND_FAILED
COMMAND_CANCELLED
COMMAND_INTERRUPTED
UNKNOWN
```

`serverEpoch` identifies a server session; `sequence` starts at 1 and is
contiguous. `EventBatch` exposes `oldestAvailableSequence`,
`latestSequence`, `nextSequence` and `missed`.

## 8. Error codes

```text
INVALID_ARGUMENT
INVALID_POSITION
INVALID_VECTOR
ROBOT_NOT_FOUND
ROBOT_NOT_LOADED
ROBOT_REMOVED
ROBOT_UNLOADED
ROBOT_UNAVAILABLE
QUEUE_FULL
INBOX_FULL
BATCH_TOO_LARGE
DUPLICATE_REQUEST
CORRELATION_CONFLICT
UNKNOWN_CORRELATION
ALREADY_TERMINAL
BACKPRESSURE
UNSUPPORTED_COMMAND
EXECUTION_FAILED
CANCELLED
INTERRUPTED
CONTRACT_VERSION_UNSUPPORTED
TIMEOUT
INTERNAL_ERROR
```

## 9. Serialization

- use record component `camelCase` names;
- UUID/resource IDs as strings;
- `CommandSpec` uses a `type` discriminator with flat fields;
- enums use `name()`;
- unknown fields ignored; missing required fields rejected;
- collection limits are enforced;
- no wire codec is provided by this version.

## 10. Packaging and runtime

The main mod uses:

```kotlin
implementation(project(":contract"))
```

The root `jar` task merges contract classes. Verify:

```text
jar tf build/libs/swarmie-0.1.1.jar | findstr dev/swarmie/contract
```

`SwarmieMod` reads `ContractVersion.CURRENT` at startup, proving runtime class
loading.

0.1.1 release artifact:

```text
build/libs/swarmie-0.1.1.jar
238,293 bytes
SHA-256 0913341d63e587200d5bbccc91e1309e4ee7297e36abaa31ece56d60490ed031
```

## 11. Not implemented

- registry/event store/serverEpoch: implemented in Prompt 2;
- local I/O/idempotency/batch: implemented in Prompt 3;
- command specs: implemented;
- remote network transport: not implemented; localhost JSONL is debug-only;
- GUI container support: not implemented.
