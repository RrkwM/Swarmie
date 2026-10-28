English | [简体中文](test-matrix.zh-CN.md)

# Final Test Matrix — Adapter Refactor

> Evidence classes:
> **A** = automated JUnit; **C** = real Minecraft client; **S** = static/build;
> **U** = unverified; **L** = known limitation.

## 1. Automated and static

| Item | Evidence | Result |
|---|---|---|
| `:contract` independent compile classpath | S | `compileClasspath: No dependencies` |
| Contract has no Minecraft/Fabric imports | S | 23 source files checked |
| Contract classes in final JAR | S | `dev/swarmie/contract/*.class` |
| `:contract:test` | A | 7 passed |
| Root `test` | A | 39 passed |
| Total | A | **46 passed** |
| No-cache test | A | `cleanTest test --no-build-cache` passed |
| Offline build | S | `--offline build` passed |
| 0.1.0 release JAR | S | 162,555 bytes, SHA-256 `fe3007e7df6ba694e6291149f49b50ae48cedabfa2946e56c42f9d0502a34f9a` |
| Spawn egg item/model/recipe/advancement/lang data | A | 3 data-contract tests passed |

Test classes:

```text
ContractTest                  7
AdapterEventBusTest           2
AdapterEventStoreTest         3
InboundQueueTest              8
RobotRegistryTest             4
FlightControllerTest          2
InteractionRuntimeTest        5
SwarmieRuntimeEventTest       4
SwarmieRuntimeTest            5
SensingTest                   3
SwarmieSpawnEggDataTest       3
```

## 2. Real client

Minecraft 26.3 / Fabric Loader 0.20.1 / Fabric API 0.165.0+26.3.
World: disposable `New World`, real integrated server.

| Item | Evidence | Result |
|---|---|---|
| Client startup/main menu/world | C | passed |
| Contract class load | C | `Adapter contract loaded: v0.1` |
| Entity summon/render | C | passed |
| TimedFlight 20 ticks | C | `TIMED_FLIGHT SUCCEEDED` |
| Stop | C | `STOP SUCCEEDED` |
| Single-wall collision | C | baseline passed; not rerun |
| Runtime FIFO/cancel/failure isolation | C + A | baseline client passed; automation passed |
| Iron-pickaxe mining + block removal | C | `MINE_BLOCK SUCCEEDED`, target air |
| Mining air | C | `MINE_BLOCK FAILED (target is air)` |
| Tool durability | C | baseline `damage=1`; not separately rerun |
| Stone placement/inventory consumption | C | `useItemOn` succeeded; target stone; inventory empty |
| Block query | C | stone/air correct |
| Sensor scan | C | `range=8, blocks=0, entities=0` (no targets) |
| Save/reload | C | baseline; client restart restored world |
| Chunk unload/reload | C | 4 robots UNLOADED→LOADED, UUID unchanged |
| Active removal | C | KILLED→REMOVED |
| Dimension change | C | overworld→nether, UUID unchanged, no REMOVED |
| Command unload interruption | C | `COMMAND_INTERRUPTED (robot unloaded)` |
| Dimension-change active command | C | `COMMAND_INTERRUPTED state=INTERRUPTED` |
| `serverEpoch` session change | C | random values changed |
| Adapter submit | C | `ACCEPTED state=QUEUED` |
| Idempotent duplicate | C | `DUPLICATE state=SUCCEEDED` |
| Correlation conflict | C | `CORRELATION_CONFLICT` |
| Event order | C | enqueued→accepted→started→completed |
| Public cancel (active) | C | `ACCEPTED` → `COMMAND_CANCELLED (cancelled while active)` |
| Cancel unknown/terminal | C | `UNKNOWN`/`UNKNOWN_CORRELATION`; `ALREADY_TERMINAL` |
| Localhost JSONL + Python | C | mine/fly/stop/duplicate/cancel/reconnect passed |
| Idempotency TTL 600 s | A | terminal expires; QUEUED does not |
| Spawn egg registration + asset/data load | C | log: `Swarmie spawn egg registered`; no model/recipe errors |
| Spawn egg given + creative search | C | `Gave 1 [Swarmie Spawn Egg]`; search `swarmie` returned the item |
| Recipe unlock / auto-fill / craft | C | `Unlocked 1 recipe(s)`; recipe book filled the 3×3 grid; output crafted |
| Right-click spawn | C | new `ROBOT_LOADED` `ff69dd53…`; inventory `empty` |
| Event-ring live overflow | U | not filled to 1024; small-capacity JUnit |
| Batch live submission | U | JUnit covers limits/per-item semantics; no live batch command |
| Second-client sync | U | not tested |
| Dedicated server | U | not tested |
| GUI containers | L | FakePlayer.openMenu no-op; success not content change |
| LOS sensor | L | unsupported |
| High-speed/diagonal/stair/ceiling collision | U | baseline did not cover |

## 3. Lifecycle details

- 4 robots loaded → 4 ROBOT_LOADED.
- 4 × 1000-tick flight → 4 COMMAND_ACCEPTED + 4 COMMAND_STARTED.
- Player moved away → each robot COMMAND_INTERRUPTED then ROBOT_UNLOADED.
- Returned → same UUIDs reloaded, no duplicate records.
- `/execute in minecraft:the_nether run tp @e[type=swarmie:swarmie] ...` →
  ROBOT_UNLOADED + ROBOT_DIMENSION_CHANGED, no ROBOT_REMOVED.
- Player entered nether → same UUIDs loaded in nether.
- `/kill` → ROBOT_UNLOADED + ROBOT_REMOVED (KILLED).
- Events contiguous; `missed=false`.

## 4. Conclusion

Achieved:

- independent contract, four commands, packaged JAR, client class load;
- registry across unload, dimension changes not treated as deletion, event ring
  gap detection;
- bounded inbox, idempotency/conflict/backpressure/batch unit semantics;
- Runtime command events and correlation;
- real client lifecycle/removal/interruption/Adapter submit/Python;
- stone placement and inventory consumption;
- spawn egg item, model, recipe, advancement and lang data (unit-checked);
- real client: egg given and found in creative search; recipe unlocked and
  crafted from a 3×3 grid through the recipe book; right-click spawned a new
  robot with an empty inventory.

Not claimed:

- remote/public connectivity, TLS, authentication or multi-user security;
- GUI containers;
- second client, dedicated server, live ring overflow, all collision boundaries.
