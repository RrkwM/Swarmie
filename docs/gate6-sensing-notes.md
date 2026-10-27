# Gate 6 — World queries and local sensors

> Scope: source implementation + pure-geometry/validation tests. Gate 1 remains
> blocked; **no live-world query or sensing has been verified**.

## 1. Two independent capabilities

| Capability | Interface | Default | Owner |
|---|---|---|---|
| World query | `dev.swarmie.query.WorldQuery` | `DefaultWorldQuery` | `SwarmieEntity.worldQuery()` / `setWorldQuery(...)` |
| Local sensor | `dev.swarmie.sensor.LocalSensor` | `DefaultLocalSensor` | `SwarmieEntity.localSensor()` / `setLocalSensor(...)` |

- They are separate interfaces/types and can be replaced independently.
- `DefaultWorldQuery` is stateless; `DefaultLocalSensor` is stateless apart from
  per-call validation.
- No global registry, plugin loader, event bus or DI container.
- The Runtime is not involved in query iteration or sensor filtering.

## 2. World-query API

```java
BlockQueryResult queryBlock(ServerLevel level, BlockPos pos);
RegionQueryResult queryRegion(ServerLevel level, BlockPos from, BlockPos to);
EntityQueryResult queryEntities(
        ServerLevel level, AABB box, Vec3 origin, EntityType<?> typeFilter, UUID exclude);
```

### 2.1 Result states

`QueryStatus`: `OK`, `UNLOADED`, `OUT_OF_BOUNDS`, `INVALID`, `WRONG_THREAD`,
`UNSUPPORTED`, `ERROR`.

- Air is `OK` with `BlockSnapshot.air() == true` and `minecraft:air`.
- An unloaded chunk is `UNLOADED`, never air/empty.
- An out-of-world/build-height position is `OUT_OF_BOUNDS`.
- Off-thread calls return `WRONG_THREAD`; no thread pool or async dispatch.
- Queries are read-only.

`BlockSnapshot` retains no live `Level`/`BlockState`:

```text
BlockPos pos
Identifier blockId
Map<String,String> properties
boolean air
```

### 2.2 Region limits

- Inclusive volume maximum: **4,096** block positions.
- `DefaultWorldQuery.regionVolume(from,to)` validates with `long` dimensions and
  overflow guards before iterating.
- Reversed corners are normalized.
- Iteration checks each position:
  - outside build height / world border → `unavailable`;
  - chunk not loaded → `unavailable`;
  - otherwise `getBlockState` and snapshot.
- `RegionQueryResult.complete` is true only when `unavailable` is empty. Missing
  positions are listed explicitly; no silent holes.
- No `getChunk`/implicit chunk loading is used.

### 2.3 Entity queries

- Uses `Level.getEntities(Entity, AABB, Predicate)` (server-side native query).
- Bounded AABB volume: **65,536**; result cap **256** with `truncated`.
- Summaries contain type id, UUID, position, copied AABB and distance to origin.
- Optional type filter and UUID exclusion (typically the querying Swarmie).
- `complete` checks chunk coverage of the AABB; unloaded chunks set `complete=false`.

## 3. Local sensor API

```java
SensorObservation observe(SwarmieEntity robot, SensorConfig config);
```

The sensor accepts the robot entity, not caller-supplied coordinates. It reads the
robot's authoritative `position()` and `getViewVector(1.0F)` itself; callers cannot
use it to scan an arbitrary origin.

Default configuration:

| Parameter | Default | Maximum |
|---|---|---|
| range | 8 blocks | 16 |
| FOV | 90° full cone | 180° |
| maxResults | 32 combined hits | 64 |
| lineOfSight | false | unsupported if true |

- Direction is `SwarmieEntity.getViewVector(1.0F)` from the authoritative
  server-side yaw/pitch.
- FOV is a **3D cone** around the view vector; `withinCone` uses
  `cos(fov/2)` with an epsilon. A block being nearby is not sufficient.
- Scan volume is bounded by range; no arbitrary-coordinate API is exposed.
- Blocks and entities are merged, sorted by distance, then limited to
  `maxResults`; `truncated` reports clipping.
- Unloaded/out-of-world positions and unloaded chunks set `incomplete=true`.
- Air is not emitted as a detected object; it is not treated as an error either.
- `lineOfSight=true` returns `UNSUPPORTED` with an explicit reason. No custom ray
  engine or false visibility claim is implemented.

`SensorObservation` is an immutable point-in-time snapshot:

```text
QueryStatus status
Vec3 origin, Vec3 direction
SensorConfig config
List<SensorBlock> blocks
List<SensorEntity> entities
boolean truncated, incomplete
String reason
```

`SensorBlock`/`SensorEntity` carry the immutable summary, relative vector,
distance, and (entities) angle in degrees.

## 4. Chunk/thread policy

- Every block read is guarded by `ServerLevel.isLoaded(pos)` first.
- No API that implicitly loads chunks is called for a query.
- Unloaded chunks produce `UNLOADED`, unavailable positions, `complete=false`, or
  `incomplete=true`; never fabricated air/empty data.
- Both capabilities check `MinecraftServer.isSameThread()` and reject off-thread
  calls with `WRONG_THREAD`. No new thread pool exists.
- Sensor output is a snapshot and holds no `Level`/`Entity` references.

## 5. Entity access

`SwarmieEntity` exposes:

```java
worldQuery() / setWorldQuery(WorldQuery)
setWorldQueryEnabled(boolean) / isWorldQueryEnabled()
queryBlock(BlockPos) / queryRegion(BlockPos, BlockPos)

localSensor() / setLocalSensor(LocalSensor)
setLocalSensorEnabled(boolean) / isLocalSensorEnabled()
sense(SensorConfig)
```

`queryBlock`/`queryRegion` honour the query enable switch; `sense` returns an
`UNSUPPORTED` observation when the sensor is disabled. `sense` uses the robot's
`position()` and `getViewVector(1.0F)`; the sensor receives the entity, not
caller-supplied coordinates. Both capabilities can be replaced independently
through their setters.

`DefaultWorldQuery` is stateless. The diagnostic `query` commands use a
server-level default query instance; the entity-level switches govern entity
capability use.

## 6. Diagnostic commands

All require `Commands.LEVEL_GAMEMASTERS`.

| Command | Effect |
|---|---|
| `/swarmie query block <x> <y> <z>` | Absolute `queryBlock`; prints id, air flag, properties |
| `/swarmie query region <x1> <y1> <z1> <x2> <y2> <z2>` | Bounded `queryRegion`; prints volume/count/unavailable/complete and first blocks |
| `/swarmie sensor scan <target> <range>` | Calls the selected Swarmie's `sense`; prints range/FOV/counts/truncation/incomplete and first hits |

`range` is limited to `(0, 16]`; non-Swarmie sensor targets are rejected. Large
results are summarized, never flooded.

## 7. Static validation

Production + client compile:

```text
javac @tools/provision-tmp/diagnostics/javac.args
```

Result: **exit 0**.

Pure-logic tests:

```text
javac @tools/provision-tmp/diagnostics/sensing-probe.args  -> 0
java -cp ... SensingProbe                                  -> 0
SENSING_PROBE_OK
```

Covered:

- region single-block/exact-limit/over-limit/reversed-corner volume;
- extreme-coordinate overflow handling;
- sensor range/FOV/result-limit validation;
- exact 45° FOV boundary in/out, behind-target rejection, angle calculation;
- air vs unloaded result-state distinction;
- immutable block properties, region lists, sensor lists, entity summaries;
- result-limit helper and truncation predicate;
- independence of two capability instances.

Not testable without a real server world:

- actual `isLoaded`/world-border behavior;
- `Level.getEntities` contents and chunk completeness;
- sensor scans of real blocks/entities, truncation from real hits;
- line-of-sight (explicitly unsupported);
- thread-check behavior in a live server.

## 8. Known limitations

- Gate 1 still blocks Loom build/JAR/live loading.
- No live-world query/sensor test; chunk semantics remain API-level only.
- Sensor FOV is a 3D cone, not a rectangular screen-space FOV.
- Line-of-sight occlusion is not implemented; enabling it returns `UNSUPPORTED`.
- Entity queries only see entities the native loaded-world query exposes; unloaded
  chunks are marked incomplete, not loaded.
- No sensors for fluids, inventories, light, redstone or block entities beyond
  block-state/property snapshots.
- No AI, planning, pathfinding, GUI, networking or multi-agent coordination.

## 9. Live testing procedure (after Gate 1)

1. Build/load the mod, summon a Swarmie in a loaded area.
2. `/swarmie query block <x> <y> <z>` on air, stone, and an unloaded/far chunk.
3. `/swarmie query region` over a small known area; verify counts, properties and
   `complete=false` near unloaded chunks.
4. Position/orient the Swarmie toward a block within 8 blocks:
   `/swarmie sensor scan @e[type=swarmie:swarmie,limit=1] 8`
   Verify forward-only inclusion, range exclusion, sorted distances and
   `truncated` when >32 hits.
5. Point the Swarmie away and confirm the same nearby block is excluded.
6. Test a range above 16 and a non-Swarmie target; confirm explicit rejection.
7. Confirm no chunks load as a side effect of queries/sensing.
