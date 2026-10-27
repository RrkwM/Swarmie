# Player Guide

> Short Wiki mirror. The versioned full guide is
> [docs/player-guide.md](https://github.com/RrkwM/Swarmie/blob/main/docs/player-guide.md).

## Requirements

- Minecraft 26.3
- Fabric Loader 0.20.1
- Fabric API 0.165.0+26.3
- Java 25

## Install

1. Download `swarmie-0.1.0.jar` from
   [Releases](https://github.com/RrkwM/Swarmie/releases/tag/v0.1.0).
2. Put it in `mods/` together with Fabric API.
3. Launch the game.

## Get a robot

0.1.0 has no recipe, item, or spawn egg. As an OP:

```mcfunction
/summon swarmie:swarmie ~ ~ ~
```

Use the namespaced selector:

```text
@e[type=swarmie:swarmie]
```

## First commands

```mcfunction
/swarmie status @e[type=swarmie:swarmie,limit=1]
/swarmie fly @e[type=swarmie:swarmie] 0.1 0 0 20
/swarmie stop @e[type=swarmie:swarmie]
/swarmie results @e[type=swarmie:swarmie,limit=1]
```

All `/swarmie` commands require OP/permission level 2.

## Important limits

- No survival acquisition yet.
- No GUI container support.
- Mining drops are not auto-collected.
- `inventory give` does not switch the selected slot; `use` uses the selected
  slot.
- Commands are interrupted on unload and do not resume.
- 0.1.0 is pre-1.0.

Full command reference, troubleshooting and lifecycle details:
[Player Guide](https://github.com/RrkwM/Swarmie/blob/main/docs/player-guide.md).
