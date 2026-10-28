English | [简体中文](player-guide.zh-CN.md)

# Player Guide

> Applies to **Swarmie 0.1.0** (latest release) and the unreleased
> `0.1.1-SNAPSHOT` development build. This is an administrator/developer
> preview, not a complete survival gameplay implementation.

## 1. Requirements

| Component | Version |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.165.0+26.3 |
| Java | 25 |

Swarmie will not load without Fabric API or with a JDK below 25.

## 2. Install

1. Install Fabric Loader 0.20.1 for Minecraft 26.3.
2. Download from [Releases](https://github.com/RrkwM/Swarmie/releases):
   - `swarmie-0.1.0.jar`
   - `swarmie-0.1.0.jar.sha256`
3. Put `swarmie-0.1.0.jar` in the game `mods/` directory.
4. Put the matching Fabric API JAR in `mods/` too.
5. Start the game. The log should contain:

```text
Swarmie initialized; registered entity type swarmie:swarmie
```

### Verify the download

```powershell
Get-FileHash .\swarmie-0.1.0.jar -Algorithm SHA256
```

Compare it with the SHA-256 published on the Release page.

## 3. How to get a robot

### 0.1.0 release

The 0.1.0 release has no robot item or crafting recipe. An
OP/permission-level-2 player must use:

```mcfunction
/summon swarmie:swarmie ~ ~ ~
```

### Unreleased 0.1.1-SNAPSHOT (main)

The unreleased `0.1.1-SNAPSHOT` build adds a craftable spawn egg:

| Item | Recipe | Notes |
|---|---|---|
| Swarmie Spawn Egg (`swarmie:swarmie_spawn_egg`) | Shaped: 4 iron ingots, 4 redstone, 1 copper ingot | Spawn Eggs creative tab; creates a **new** robot with a fresh UUID and empty 9-slot inventory |

Using the egg never captures, stores or restores an existing robot; it always
creates a new one. `/summon` remains supported.

Find loaded robots:

```mcfunction
/swarmie status @e[type=swarmie:swarmie,limit=1]
```

Use the namespaced selector:

```text
@e[type=swarmie:swarmie]
```

Do not write only `swarmie`; it is parsed as `minecraft:swarmie`.

## 4. Basic commands

All `/swarmie` commands require OP/permission level 2.

### Flight and stop

```mcfunction
/swarmie fly @e[type=swarmie:swarmie] 0.1 0 0 20
/swarmie stop @e[type=swarmie:swarmie]
```

`fly` arguments:

```text
x y z         velocity in blocks/tick
duration      ticks, range 1..6000
```

This controls velocity; it is not "move to coordinates". The robot decelerates
after the duration.

### Status and results

```mcfunction
/swarmie status @e[type=swarmie:swarmie,limit=1]
/swarmie results @e[type=swarmie:swarmie,limit=1]
```

- `status`: current command, queued count and retained results;
- `results`: the latest five command results.

### Cancel

```mcfunction
/swarmie cancel @e[type=swarmie:swarmie]
/swarmie cancelall @e[type=swarmie:swarmie]
/swarmie cancelid @e[type=swarmie:swarmie] 3
```

A queued command is cancelled before execution. Active flight stops, but
movement already produced cannot be rolled back.

### Mining

```mcfunction
/swarmie mine @e[type=swarmie:swarmie,limit=1] 100 64 100
```

- target must be within the 6-block interaction range;
- target chunk must be loaded;
- drops remain in the world and are **not** automatically collected;
- the robot's selected slot is used.

### Use item / place

```mcfunction
/swarmie use @e[type=swarmie:swarmie,limit=1] 100 63 100 up
```

Faces:

```text
down up north south east west
```

- uses the robot's currently selected slot;
- `inventory give` inserts items but does not necessarily select the slot;
- standard GUI containers (chests, furnaces, hoppers, ...) cannot actually be
  opened;
- a successful chest interaction does not mean contents changed.

### Inventory

```mcfunction
/swarmie inventory give @e[type=swarmie:swarmie,limit=1] minecraft:stone 1
/swarmie inventory show @e[type=swarmie:swarmie,limit=1]
```

Nine slots; the selected slot is marked with `*`.

### Query and sensor

```mcfunction
/swarmie query block 100 64 100
/swarmie query region 100 64 100 110 74 110
/swarmie sensor scan @e[type=swarmie:swarmie,limit=1] 8
```

Queries are read-only and do not implicitly load chunks. The sensor returns at
most 64 results; line-of-sight is not supported.

### Low-level velocity (debug)

```mcfunction
/swarmie velocity @e[type=swarmie:swarmie] 0.1 0 0
```

This bypasses the Runtime queue and command results. Use `fly`/`stop` for normal
operations.

## 5. Lifecycle

- UUID and inventory survive chunk unload/reload;
- active and queued commands become `INTERRUPTED` on unload and do not resume;
- dimension changes are not treated as permanent deletion;
- `/kill` and other permanent removal produce `REMOVED`;
- command results and events do not persist across server processes.

## 6. Troubleshooting

| Symptom | Action |
|---|---|
| No Swarmie at startup | Check Java 25, Fabric Loader/API, and the JAR in `mods/` |
| `/swarmie` says no permission | Requires OP/permission level 2 |
| Robot not found | Summon it or use the spawn egg, and use `@e[type=swarmie:swarmie]` |
| `MINE_BLOCK FAILED (target is air)` | Choose a solid block |
| `target is outside interaction range` | Move within 6 blocks |
| `UNLOADED_CHUNK` | Load the target chunk first |
| Chest does not open | FakePlayer GUI containers are unsupported |
| Placement returns `Pass[]` | The selected item cannot be used on that block |

## 7. Current limitations

- The unreleased spawn egg only creates new robots; there is no item that picks
  up, stores or recalls an existing robot;
- no AI, pathfinding, obstacle avoidance or task orchestration;
- no GUI-container support;
- no remote/public network; localhost JSONL is developer-only;
- dedicated server, second client, LOS and some collision boundaries are
  unverified;
- 0.1.0 is pre-1.0; behavior and Adapter contracts may change.

## 8. Next

- [Developer Guide](developer-guide.md)
- [Adapter command specs](adapter-command-specs.md)
- [Test matrix](test-matrix.md)
- [FAQ](wiki/FAQ.md)
