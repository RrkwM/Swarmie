# FAQ / Troubleshooting

## The mod does not load

Check:

- Minecraft **26.3**
- Fabric Loader **0.20.1**
- Fabric API **0.165.0+26.3**
- Java **25**
- the JAR is in `mods/`, not the world folder

## `/swarmie` says I do not have permission

All commands use `LEVEL_GAMEMASTERS`; use an OP/permission-level-2 account.

## My selector finds nothing

Use the namespaced ID:

```text
@e[type=swarmie:swarmie]
```

`swarmie` alone is parsed as `minecraft:swarmie`.

## Mining fails

Common causes:

- target is air;
- target is outside the 6-block interaction range;
- target chunk is not loaded;
- the block is protected or unbreakable.

Mining drops stay in the world and are **not** automatically collected.

## Placing/using an item fails

`/swarmie use` uses the robot's currently selected slot. `/swarmie inventory
give` inserts an item but does not necessarily select that slot. Some items
return `Pass` for a given block.

## A chest does not open

Known limitation: the FakePlayer path does not open a real GUI/menu. A returned
success does not mean the container contents changed.

## A command disappeared after chunk unload

Commands are interrupted on unload and are not restored after reload. This is
intentional; see the lifecycle docs.

## Can I connect an external program?

Only through the default-off, loopback-only JSONL development transport. It has
no authentication/TLS and is not a remote or multi-user service.

## Why is there no recipe/item?

0.1.0 is an admin/developer preview. Survival acquisition is not implemented
yet; `/summon` is the current entry point.
