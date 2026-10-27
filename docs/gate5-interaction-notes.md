# Gate 5 — Native world interaction notes

> Scope: source implementation + pure-logic tests. Gate 1 (Loom build environment)
> remains blocked; **no live-world interaction has been verified**.

## 1. Robot inventory

- `dev.swarmie.inventory.SwarmieInventory`, 9 slots, implements `Container`.
- Authoritative owner is `SwarmieEntity`; it is not a view over a player inventory.
- `selectedSlot` is clamped to `0..8`; `getSelectedStack()` returns the slot stack.
- `setItem` copies incoming stacks and clamps counts to the item's default max stack
  size. `insertItem` copies and merges into matching stacks first, then empty slots,
  returning the remainder.
- Persistence uses the verified 26.3 APIs:
  - `ContainerHelper.saveAllItems(ValueOutput, NonNullList<ItemStack>)`
  - `ContainerHelper.loadAllItems(ValueInput, NonNullList<ItemStack>)`
  - `ValueOutput.putInt` / `ValueInput.getIntOr` for `SelectedSlot`
- Saved under the entity child `"SwarmieInventory"`. Loading clears first, sanitizes
  null/empty/oversized stacks, clamps the selected slot, and resets to empty on a
  `RuntimeException` from malformed data.
- No armor, GUI, automation, or custom sync framework.

## 2. InteractionActor

`dev.swarmie.interaction.InteractionActor` implements `InteractionHandler` and
`AutoCloseable`:

- Owns one temporary `FakePlayer` subclass (`SwarmieFakePlayer`) per actor, created
  from a random `GameProfile`. The fake player inherits the default player game
  mode; `changeGameModeForPlayer` is deliberately **not** called because it sends
  player-list packets and a fake player has no connection.
- The fake player is **not added to the world** and is never the robot body.
- Context is cached only while the same `ServerLevel` and non-removed player remain
  valid; otherwise it is closed and rebuilt.
- `close()` clears the fake inventory and discards the fake player.
- `SwarmieEntity.onRemoval` closes the actor for permanent removals, not ordinary
  chunk unload.

Verified API facts:

- `net.fabricmc.fabric.api.entity.FakePlayer extends ServerPlayer`; protected
  constructor `(ServerLevel, GameProfile)`.
- `ServerPlayer.gameMode` is a public `ServerPlayerGameMode`.
- `ServerPlayerGameMode.destroyBlock(BlockPos)` returns `boolean`.
- `ServerPlayerGameMode.useItemOn(ServerPlayer, Level, ItemStack, InteractionHand,
  BlockHitResult)` returns `InteractionResult`; success is checked with
  `InteractionResult.consumesAction()`.
- `BlockHitResult(Vec3, Direction, BlockPos, boolean)` exists.
- `ServerLevel.isLoaded(BlockPos)`, `getWorldBorder().isWithinBounds(BlockPos)`,
  `mayInteract(Entity, BlockPos)`, `getBlockState(BlockPos)`,
  `BlockState.getDestroySpeed(BlockGetter, BlockPos)`.

## 3. Validation and interaction rules

Before an interaction:

- robot must not be removed;
- level must be a `ServerLevel`;
- target chunk must already be loaded (`isLoaded`, no implicit load);
- target must be inside the world border;
- distance from robot to block center must be `<= 6.0` blocks;
- `level.mayInteract(fakePlayer, pos)` must allow it;
- air is rejected; mining additionally rejects `getDestroySpeed < 0`.

Mining uses `fakePlayer.gameMode.destroyBlock(pos)`. No `setBlock(AIR)`, no manual
drops, no simulated break animation.

Use-on-block uses `fakePlayer.gameMode.useItemOn(...)` with
`InteractionHand.MAIN_HAND` and a `BlockHitResult`. The clicked block position and
face are distinct from the eventual placement position; native item/placement rules
decide the result. No direct `setBlockState`.

## 4. Inventory handoff

For each operation:

1. Capture the robot's selected stack (`copy`).
2. Clear the fake player inventory, set selected slot 0, insert a copy.
3. Set fake player position/rotation to the robot.
4. Execute the native operation.
5. In `finally`, read fake slot 0; copy back to the robot **only if the robot slot
   still matches the captured stack** (`ItemStack.matches`), so newer robot-side
   changes win.
6. Clear the fake inventory again.

Exceptions are caught and returned as `InteractionOutcome.failure`; cleanup still
runs. The robot inventory remains authoritative throughout.

## 5. Runtime integration

`SwarmieRuntime` now accepts an optional `InteractionHandler`:

- `submitMineBlock(BlockPos)`
- `submitUseItemOn(BlockPos, Direction, Vec3 hit)`

New command types: `MINE_BLOCK`, `USE_ITEM_ON_BLOCK`. They use the same FIFO queue,
IDs, state machine and history as Gate 4. Interaction commands run synchronously in
the server tick:

- start calls the handler; expected failure throws `CommandFailureException` and
  becomes `FAILED` with the reason;
- success becomes terminal on the following tick (one tick of command bookkeeping,
  not an asynchronous world operation);
- cancellation of an already-started synchronous interaction cannot undo a world
  change; cancellation before start prevents it. Queued cancellation remains exact.
- Unexpected exceptions become `FAILED` and are logged; Runtime continues.

## 6. Debug commands

All require `Commands.LEVEL_GAMEMASTERS`.

| Command | Effect |
|---|---|
| `/swarmie mine <target> <x> <y> <z>` | Queue a native `destroyBlock` command |
| `/swarmie use <target> <x> <y> <z> <face>` | Queue native `useItemOn` against the block face |
| `/swarmie inventory give <target> <item> <count>` | Privileged test insertion into the robot inventory |
| `/swarmie inventory show <target>` | Inspect selected slot and contents |

Faces use `Direction.byName` (`up`, `down`, `north`, `south`, `east`, `west`).
Item arguments use `IdentifierArgument` + `BuiltInRegistries.ITEM`; count is
`1..item.defaultMaxStackSize`. Mining/use go through Runtime, not direct world
mutation. Inventory give is the only direct inventory debug path.

## 7. Static validation

Production + client compilation:

```text
javac @tools/provision-tmp/diagnostics/javac.args
```

Result: **exit 0** (inventory, interaction actor, commands, runtime, client renderer).

Pure-logic tests:

```text
javac @tools/provision-tmp/diagnostics/interaction-probe.args  -> 0
java -cp ... InteractionProbe                                  -> 0
INTERACTION_PROBE_OK
```

Covered:

- 9 slots, selected-slot clamping, empty-stack handling, slot bounds, clear.
- Runtime interaction command success, failure -> `FAILED` with reason, Runtime
  continuing afterward.
- FIFO ordering, queued cancellation before execution, command IDs.
- no-handler rejection, null position rejection, non-finite hit rejection.

Not covered by pure tests (requires full game bootstrap/live world):

- item-count merge/clamp and `ItemStack` persistence;
- inventory handoff / tool durability / item consumption;
- fake-player creation, `mayInteract`, drops, placement, events, mod hooks;
- `Entity.move` collision already documented as runtime-unverified.

## 8. Known limitations

- Gate 1 remains blocked: no Loom build, no Mod JAR, no in-game test.
- `FakePlayer` has no real connection; `useItemOn` behavior for items that require
  connection/client state is unverified. Native calls are used, not a fallback to
  direct world mutation. The default fake-player game mode is assumed to be
  survival; this is not runtime-verified.
- Protection/mod-hook behavior depends on the native call path; no third-party mods
  tested.
- Inventory count/merge/persistence tests could not run without Minecraft's full
  item bootstrap; source is compiled but behavior is unverified.
- Synchronous interaction cancellation cannot undo an already-executed world change.
- No automation, GUI, sensors, AI, or multi-robot coordination.

## 9. Live testing procedure (after Gate 1)

1. Summon Swarmie and give a tool:
   `/summon swarmie:swarmie`
   `/swarmie inventory give @e[type=swarmie:swarmie,limit=1] minecraft:iron_pickaxe 1`
   `/swarmie inventory show @e[type=swarmie:swarmie,limit=1]`
2. Mine a nearby block:
   `/swarmie mine @e[type=swarmie:swarmie,limit=1] <x> <y> <z>`
   Verify the block disappears, drops appear once, tool durability drops.
3. Place a block:
   give `minecraft:stone 8`, then
   `/swarmie use @e[type=swarmie:swarmie,limit=1] <x> <y> <z> up`
   Verify native placement, item consumption and no duplicate.
4. Test protected/out-of-range/unloaded targets and confirm explicit failure results.
5. Save/reload and confirm inventory slots and selected slot restore.
6. Confirm the fake player never appears in the world and no robot item is duplicated.
