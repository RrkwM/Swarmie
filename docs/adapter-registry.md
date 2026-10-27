English | [简体中文](adapter-registry.zh-CN.md)

# Adapter Registry, Lifecycle and Event Store

> Prompt 2. Implementation: `dev.swarmie.adapter`, `adapter.registry`,
> `adapter.event`.

## 1. Server session

Each `SERVER_STARTED` creates an `AdapterSession`:

- `serverEpoch`: random positive 63-bit value, independent of system clock;
- `RobotRegistry`: identity and last-known metadata;
- `AdapterEventStore`: bounded ring;
- a temporary loaded-entity lookup that never survives unload.

`SERVER_STOPPING` terminates loaded robots, writes unfinished command terminal
events, clears the entity lookup, and drops the event ring. No cross-process
persistence is promised.

## 2. Registry

Key: robot UUID (`RobotId`). States:

```text
LOADED
UNLOADED
REMOVED
```

`RobotDescriptor`:

```text
RobotId
dimensionId
Vec3Dto position
RobotLifecycleState lifecycle
Set<CommandType> supportedCommands
long lastUpdatedTick
```

Rules:

- chunk unload does not delete the record;
- no `Entity`/`Level` strong references;
- at most 256 REMOVED records;
- mutation only on the server thread;
- readers use the volatile immutable snapshot;
- UUID reload does not duplicate the record.

## 3. Lifecycle classification

### Load

`ServerEntityEvents.ENTITY_LOAD`:

- state `LOADED`;
- `ROBOT_LOADED`;
- if a pending dimension change exists, detail includes `dimension change complete`;
- register the Runtime listener.

### Chunk unload

`ServerEntityEvents.ENTITY_UNLOAD`:

- terminate active/queued Runtime commands (`COMMAND_INTERRUPTED`);
- close `InteractionActor`;
- `ROBOT_UNLOADED`;
- retain `UNLOADED` metadata;
- never classify unload as permanent removal.

### Permanent removal

`SwarmieEntity.onRemoval(reason)` for reasons other than
`UNLOADED_TO_CHUNK`/`UNLOADED_WITH_PLAYER`:

- `KILLED`/`DISCARDED` → `ROBOT_REMOVED`, state `REMOVED`;
- `CHANGED_DIMENSION` → no `REMOVED`; write `ROBOT_DIMENSION_CHANGED`, state
  `UNLOADED`, mark pending dimension change;
- terminate unfinished commands first.

Observed order:

```text
COMMAND_INTERRUPTED
ROBOT_UNLOADED
ROBOT_DIMENSION_CHANGED
... new entity load ...
ROBOT_LOADED (detail=dimension change complete)
```

`ROBOT_UNLOADED` is **not** a promise that the robot will return. KILLED may
produce `ROBOT_REMOVED` immediately after.

## 4. Event ring

`AdapterEventStore`:

- capacity 1024;
- `eventSequence` starts at 1 and is contiguous within `serverEpoch`;
- each event carries `serverEpoch`;
- evicts the oldest event on overflow;
- `missed=true` means the requested cursor is older than the retained range.

Read API:

```java
EventBatch batch = SwarmieAdapter.readEvents(afterSequence, maxEvents);
```

`EventBatch` exposes `oldestAvailableSequence`, `latestSequence`,
`nextSequence`, `missed`, and the events.

## 5. Runtime observation

`SwarmieRuntime.Listener`:

```text
onCommandAccepted
onCommandStarted
onCommandTerminal
```

Listener exceptions are isolated and do not change command behavior.
Events: `COMMAND_ACCEPTED`, `COMMAND_STARTED`, and
`COMMAND_COMPLETED/FAILED/CANCELLED/INTERRUPTED`.

`correlationId` is preserved; the internal command ID is not a replacement.

## 6. Debug observation

```text
/swarmie adapter status
/swarmie adapter events <after> <max>
```

These are developer diagnostics, not public contract commands.

## 7. Real-client verification

Minecraft 26.3 / Fabric Loader 0.20.1 / Fabric API 0.165.0+26.3.

- 4 robots loaded; 4 × ROBOT_LOADED.
- 4 × 1000-tick flight accepted/started.
- Player moved away: each robot produced `COMMAND_INTERRUPTED` then
  `ROBOT_UNLOADED`; status showed `loaded=0 unloaded=4`.
- Returning reloaded the same 4 UUIDs with no duplicates; old commands did not
  resume.
- `/execute in minecraft:the_nether run tp ...` produced
  `ROBOT_UNLOADED` + `ROBOT_DIMENSION_CHANGED`, no `ROBOT_REMOVED`; the same
  UUIDs reloaded in `minecraft:the_nether` with `dimensionChange=true`.
- Active flight on dimension change produced
  `COMMAND_INTERRUPTED state=INTERRUPTED detail=robot unloaded`.
- `/kill` produced `ROBOT_UNLOADED` + `ROBOT_REMOVED (KILLED)` and final
  `removed=4`.
- `serverEpoch` changed across client/server sessions.
- Event sequence remained contiguous; no live ring overflow was produced.

## 8. Known limits

- Event ring overflow is unit-tested with a small capacity, not filled live.
- No disk persistence.
- No cross-server-process event history.
