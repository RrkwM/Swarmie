English | [简体中文](developer-guide.zh-CN.md)

# Developer Guide

> Swarmie `0.1.0` / Adapter contract `0.1`. This is a pre-1.0 project; the
> public contract is not yet stable.

## 1. Stack

| Component | Version |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.165.0+26.3 |
| Loom | 1.19.3 |
| Gradle | 9.8.2 |
| Java | 25 |

Versions live in `gradle/libs.versions.toml` and `gradle.properties`.

## 2. Repository layout

```text
contract/                        pure Java contract subproject
  src/main/java/dev/swarmie/contract/
  src/test/java/dev/swarmie/contract/

src/main/java/dev/swarmie/
  SwarmieMod.java                entry point, EntityType, Fabric events
  SwarmieEntity.java             real Entity body
  SwarmieFlightController.java   velocity control
  runtime/                       FIFO and command state machine
  interaction/                   FakePlayer native interaction
  inventory/                     nine-slot inventory
  query/                         world query
  sensor/                        local sensor
  command/                       /swarmie debug commands
  adapter/
    registry/                    robot registry
    event/                       bounded event ring and bus
    io/                          inbound queue and idempotency
    transport/                   default-off localhost JSONL

src/test/                        main-mod JUnit
docs/                            specs, evidence, handoff
.github/workflows/ci.yml         CI
```

## 3. Local development

JDK 25 is required. The project-local `tools/` tree is not needed for a fresh
clone.

```bash
git clone https://github.com/RrkwM/Swarmie.git
cd Swarmie
./gradlew test
./gradlew build
./gradlew runClient
```

Windows:

```powershell
.\gradlew.bat test
.\gradlew.bat build
.\gradlew.bat runClient
```

Optional quick test world:

```powershell
.\gradlew.bat runClient -PswarmieQuickPlay="New World"
```

Output:

```text
build/libs/swarmie-0.1.0.jar
```

## 4. Layers

```text
Minecraft layer
  Entity / ServerLevel / native movement, collision, blocks/items
        │
Runtime + capabilities
  SwarmieRuntime FIFO / InteractionActor / WorldQuery / LocalSensor
        │
Adapter layer
  contract / registry / event store / inbound and outbound queues / transport
        │
Future external control plane
```

Hard rules:

- Minecraft owns position, collision and native block/item behavior;
- Runtime schedules commands; it does not traverse the world or hold a FakePlayer;
- the contract package must not import `net.minecraft.*` or `net.fabricmc.*`;
- external control uses Adapter contracts, never `SwarmieEntity`;
- classic debug commands (`velocity`, `inventory`, `query`, `sensor`) are not
  public contracts.

## 5. Contract and Adapter

### Contract

`:contract` is an independent pure Java subproject used by the main mod:

```kotlin
implementation(project(":contract"))
```

Current commands:

```java
CommandSpec.TimedFlight
CommandSpec.Stop
CommandSpec.MineBlock
CommandSpec.UseItemOnBlock
```

Core DTOs: `RobotId`, `CommandSubmission`, `SubmitAck`, `CommandResult`,
`CancelAck`, `RobotEvent`, `EventBatch`.

### Submit

```java
RobotId robot = new RobotId(uuid);
SubmitAck ack = SwarmieAdapter.submit(
        robot,
        new CommandSpec.Stop(),
        "my-correlation-id");
```

- `ACCEPTED` means inbound acceptance, not execution completion;
- same correlation/robot/command returns `DUPLICATE`;
- a different command with the same correlation returns `CORRELATION_CONFLICT`;
- terminal states (runtime full, unload, ...) are delivered as events.

### Events

```java
EventBatch batch = SwarmieAdapter.readEvents(lastSequence, 64);
AdapterEventBus.Subscription sub = SwarmieAdapter.subscribe(b -> {
    // Must return quickly; no socket/disk/blocking work here.
});
```

- `serverEpoch` identifies a server session;
- `sequence` is contiguous within an epoch;
- `missed` means the cursor is older than the oldest retained event;
- the ring holds 1024 events and evicts the oldest.

### Cancel

```java
CancelAck ack = SwarmieAdapter.cancel(robot, "my-correlation-id");
```

`ACCEPTED` only means the request is queued; the terminal event is
`COMMAND_CANCELLED`. Unknown correlations return `UNKNOWN`; already-terminal
commands return `ALREADY_TERMINAL`.

## 6. Threading and lifecycle

- `SwarmieAdapter.submit/cancel` may be called from any thread;
- submission only performs pure validation, registry snapshot, idempotency
  checks and bounded enqueue;
- entity/Runtime work runs on the server thread at `START_SERVER_TICK`;
- events are published at `END_SERVER_TICK`;
- listener exceptions are isolated, but listeners must be non-blocking;
- unload terminates Runtime active/queued and inbound commands;
- reload does not resume old commands;
- dimension changes produce `ROBOT_DIMENSION_CHANGED` and a reload, not
  `REMOVED`.

Details:

- [`adapter-registry.md`](adapter-registry.md)
- [`adapter-io.md`](adapter-io.md)
- [`adapter-command-specs.md`](adapter-command-specs.md)

## 7. Tests and evidence

```bash
./gradlew test
```

Current baseline: 43 JUnit tests.

- `contract/`: contract, batch, cancel;
- `adapter/event`: event ring and bus;
- `adapter/io`: capacity, budget, idempotency, conflicts, concurrency;
- `adapter/registry`: lifecycle states and dimension changes;
- `runtime`: FIFO, cancellation, unload interruption, events;
- interaction/sensing/flight: non-regression.

Do not substitute unit tests for a real-client check. Evidence classes and
unverified items are in [`test-matrix.md`](test-matrix.md).

## 8. Debugging

### `/swarmie` commands

- `status/results/cancel/cancelid/cancelall`
- `mine/use`
- `inventory give/show`
- `query block/region`
- `sensor scan`
- `velocity`

These are developer/admin tools, not the public Adapter API.

### localhost JSONL

Default off. See [`local-transport.md`](local-transport.md):

```text
run/config/swarmie-transport.json
```

It binds only `127.0.0.1` and has no authentication, TLS, or remote security
model. Python client:

```bash
python tools/client/swarmie_client.py --host 127.0.0.1 --port 25580
```

### Adapter diagnostics

```text
/swarmie adapter status
/swarmie adapter events <after> <max>
/swarmie adapter submit <target> stop <correlation>
```

## 9. Extension boundaries

Replaceable today:

- `WorldQuery` / `DefaultWorldQuery`;
- `LocalSensor` / `DefaultLocalSensor`;
- `InteractionHandler` / `InteractionActor`;
- internal Adapter contract, registry, events, I/O and transport.

There is no generic capability registry, script runtime, global scheduler or
network service. Adding a real command requires:

1. `CommandSpec` / `CommandType`;
2. contract tests;
3. Runtime command and Adapter dispatch;
4. command specs and test matrix;
5. version/changelog changes for public behavior.

Do not:

- use `setBlock(AIR)` instead of native mining;
- fake a complete FakePlayer connection;
- treat debug commands as public APIs;
- perform socket/disk/blocking I/O on the server tick thread;
- claim a world side effect solely because an API returned `SUCCESS`.

## 10. Versioning and releases

Swarmie follows Semantic Versioning; see [`versioning.md`](versioning.md).

Release outline:

1. update `mod_version` and remove `-SNAPSHOT`;
2. update `CHANGELOG.md`;
3. run `./gradlew test build`;
4. wait for CI success;
5. create annotated tag `vX.Y.Z`;
6. publish a GitHub Release with the JAR and `.sha256`.

## 11. Reading order

1. [`player-guide.md`](player-guide.md)
2. [`adapter-contract.md`](adapter-contract.md)
3. [`adapter-registry.md`](adapter-registry.md)
4. [`adapter-io.md`](adapter-io.md)
5. [`adapter-command-specs.md`](adapter-command-specs.md)
6. [`test-matrix.md`](test-matrix.md)
7. [`versioning.md`](versioning.md)
