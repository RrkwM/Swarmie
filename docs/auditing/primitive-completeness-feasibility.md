# Primitive Completeness Audit — Feasibility Report

> Status: feasibility prototype, not a completeness claim. No Swarmie runtime,
> contract, command or Python SDK behavior was changed.
>
> Audit date: 2026-10-29. Branch: `audit/primitive-completeness`.

## 1. Answer in brief

Yes, a versioned, reviewable, partially automated primitive audit is feasible
with the artifacts and JDK already used by this project.

The prototype:

- enumerates **64 serverbound play/common packet classes** and resolves
  **64/64 handler methods** from the class files;
- enumerates **127 clientbound play packet classes** and **122** handler
  references (5 packets have no `handle` method in the class itself);
- records **43 action/container-input variants** without treating every enum
  value as a semantic primitive;
- extracts **43 bounded passive-embodied method facts** from 11 seed classes;
- parses **67 Fabric API method/field signatures** relevant to payloads,
  receivers and item storage;
- produces a deterministic generated inventory and a reviewed catalogue with
  **32 reviewed semantic entries** covering **146 source IDs**; the remaining
  **198 sources are visibly unreviewed**;
- validates references, fingerprints, dispatch anchors, new/disappeared
  sources and nondeterminism with 14 fixture tests.

It does **not** establish that the primitive catalogue is complete. The
reviewed set is intentionally a baseline. The largest unresolved areas are
modded content, lambda/`invokedynamic` dispatch, clientbound semantic domains,
and the passive simulation surface.

## 2. Local artifacts and mappings used

All facts come from the artifacts actually resolved by this checkout:

| Role | File | Version | SHA-256 (prefix) |
|---|---|---|---|
| Minecraft server | `tools/mc/loom-cache/26.3/minecraft-extracted_server.jar` | 26.3 (protocol 777) | `a362163eec5d1612…` |
| Minecraft client | `tools/mc/loom-cache/26.3/minecraft-client.jar` | 26.3 | `4508d006323f24fa…` |
| Fabric networking API | Gradle cache `fabric-networking-api-v1` | `6.4.0+a21c9eb65d` | `0e2f968b6241e9e5…` |
| Fabric transfer API | Gradle cache `fabric-transfer-api-v1` | `8.0.25+fcdff87f5d` | `599f69de9e7e693b…` |
| Fabric lookup API | Gradle cache `fabric-api-lookup-api-v1` | `2.0.24+3434d6d95d` | `4ff3be674760c602…` |

Full hashes and sizes are in `completeness.generated.json` under `artifacts`.

Toolchain: JDK 25.0.4.1 (`java.lang.classfile` Class-File API), Gradle 9.8.2,
Loom 1.19.3, Fabric Loader 0.20.1, Fabric API `0.165.0+26.3`.

Mapping: **none**. Minecraft 26.3 ships unobfuscated classes and Loom uses its
no-remap path. No online wiki packet table, class list or method name was used
as the baseline; every class/method/field/call fact below was read from the
local class files.

The Fabric JARs are the versioned module JARs in the Gradle dependency cache,
not the `fabric-api` bundle's metadata. Regeneration needs this local cache
(or explicit `-ServerJar`/`-FabricNetJar`/... equivalent inputs); nothing is
downloaded by the audit tools.

## 3. Source inventory A — serverbound play/common inputs

### 3.1 Mechanically enumerated packet classes

`ExtractAudit.java` reads every top-level class under
`net/minecraft/network/protocol/game/` and `.../common/` whose simple name
starts with `Serverbound`, parses it with the Class-File API, and keeps it only
if it implements `net/minecraft/network/protocol/Packet`.

Result:

- 58 top-level `Serverbound*Packet` classes in `protocol/game`;
- 6 top-level common packets (`KeepAlive`, `Pong`, `CustomPayload`,
  `ResourcePack`, `ClientInformation`, `CustomClickAction`);
- 35 + 8 = **43 action/container-input source variants**.

### 3.2 Handler resolution

Each packet's `handle(ServerGamePacketListener)` (or common listener) method
contains one `invokeinterface` to the listener handler. That call is the
mechanical packet-to-handler edge. The implementation is found by signature in
`ServerGamePacketListenerImpl`, then `ServerCommonPacketListenerImpl`,
`ServerGamePacketListenerImpl` and `ServerConfigurationPacketListenerImpl`.

Result: **64/64 serverbound handler implementations found**. Examples:

```text
SB.PLAY.USE_ITEM_ON       -> handleUseItemOn   (ServerGamePacketListenerImpl)
SB.PLAY.PLAYER_ACTION     -> handlePlayerAction
SB.PLAY.ATTACK            -> handleAttack
SB.PLAY.INTERACT          -> handleInteract
SB.PLAY.PUNCH             -> handlePunch
SB.COMMON.KEEP_ALIVE      -> handleKeepAlive   (ServerCommonPacketListenerImpl)
SB.COMMON.CLIENT_INFORMATION -> handleClientInformation (ServerGamePacketListenerImpl)
```

The last one demonstrates why the implementation search is not hard-coded to
the common class: the packet's declared listener is `ServerCommonPacketListener`
but the play implementation supplies the method.

### 3.3 Discriminators

The extractor treats only fields named `action` and `containerInput` as
operation discriminators. Other enum fields (`hand`, `direction`, `difficulty`,
`mode`, `rotation`, `mirror`, `slot`, ...) are recorded as packet fields and
parameters, not as independent semantic branches. This avoids manufacturing a
"primitive" for every enum constant.

Examples of generated variants:

```text
SB.PLAY.PLAYER_ACTION.ACTION.START_DESTROY_BLOCK
SB.PLAY.PLAYER_ACTION.ACTION.STOP_DESTROY_BLOCK
SB.PLAY.PLAYER_ACTION.ACTION.ABORT_DESTROY_BLOCK
SB.PLAY.PLAYER_ACTION.ACTION.CHANGE_DESTROY_DIRECTION
SB.PLAY.PLAYER_ACTION.ACTION.DROP_ITEM
SB.PLAY.PLAYER_ACTION.ACTION.RELEASE_USE_ITEM
SB.PLAY.PLAYER_ACTION.ACTION.SWAP_ITEM_WITH_OFFHAND
SB.PLAY.PLAYER_ACTION.ACTION.STAB
SB.PLAY.CONTAINER_CLICK.CONTAINER_INPUT.QUICK_MOVE
SB.PLAY.CONTAINER_CLICK.CONTAINER_INPUT.SWAP
```

Notable 26.3 facts: `ServerboundInteractPacket` no longer carries an action
enum; it has `entityId`, `hand`, `location` and `usingSecondaryAction`.
`ServerboundAttackPacket` is a separate packet, and `ServerboundPunchPacket`
carries no fields (swing feedback). These were discovered from this JAR, not
assumed from older versions.

### 3.4 Dispatch and handler relationships

For every handler the generated inventory records:

- the exact method descriptor;
- all direct `invokevirtual`/`invokeinterface`/`invokestatic`/`invokespecial`
  calls (deduplicated, first-seen order);
- branch/switch count;
- `invokedynamic` count and bootstrap descriptors;
- a method code fingerprint.

This deliberately records **all** direct calls, not just the first virtual call.
For example, `handleUseItemOn` continues after
`ServerPlayerGameMode.useItemOn` to advancement triggering, block-placement
detection, swing handling and a block update; `handlePlayerAction` continues to
acknowledgement and a direction/sequence guard after the block-break dispatch.

## 4. Source inventory B — passive embodied interactions

There is no finite "list every world simulation method" scan. The prototype
uses a bounded seed list of embodied classes:

```text
Entity, LivingEntity, Player, ServerPlayer, ItemEntity,
BlockBehaviour$BlockStateBase, BasePressurePlateBlock,
PressurePlateBlock, TripWireBlock, PortalProcessor, Level
```

and a bounded method-name pattern (`tick`, `baseTick`, `aiStep`, `travel`,
`move`, `collide`, `checkInsideBlocks`, `entityInside`, `playerTouch`, `hurt`,
`hurtServer`, `attack`, `interact`, `take`, `drop`, `pickup`, `handlePortal`,
`setAsInsidePortal`, `processPortalTeleportation`, `checkBelowWorld`,
`isInWall`, `isInsidePortal`).

Result: **43 method facts** from those classes, including:

- motion/collision: `Entity.move`, `Entity.collide`, two
  `Entity.checkInsideBlocks` overloads;
- block-driven effects: `BlockStateBase.entityInside` →
  `Block.entityInside`, plus `BasePressurePlateBlock.entityInside` and
  `TripWireBlock.entityInside`;
- pickup: `ItemEntity.playerTouch` → `Inventory.add` / `Player.take`;
- damage: `LivingEntity.hurtServer` (and overrides), with the base
  `Entity.hurtServer` having no calls;
- portal: `Entity.handlePortal` → `PortalProcessor.processPortalTeleportation`.

The important negative result: the `entityInside` dispatch from
`Entity.checkInsideBlocks` is not a direct invocation in the method body. It is
reached through `BlockGetter.forEachBlockIntersectedBetween` and a captured
`visit` lambda (`invokedynamic`). The generated inventory records the lambda
count but **cannot** resolve its target with this extractor. That is a concrete
blind spot, not an approximated edge presented as fact.

## 5. Source inventory C — clientbound observations

The extractor enumerates 127 top-level `Clientbound*Packet` classes in
`protocol/game` and records fields, handler method references and class hashes.

- 122/127 expose a `handle(ClientGamePacketListener)` reference;
- 5 have no `handle` method in the class itself:
  `CB.PLAY.BUNDLE_DELIMITER`, `CB.PLAY.SET_ACTION_BAR_TEXT`,
  `CB.PLAY.SET_SUBTITLE_TEXT`, `CB.PLAY.SET_TITLES_ANIMATION`,
  `CB.PLAY.SET_TITLE_TEXT`.

The generated file does not assign state domains automatically. That is a
semantic judgment. The reviewed catalogue maps representative groups:

| Reviewed group | Examples | Disposition |
|---|---|---|
| `SEM-019` block observations | `BLOCK_UPDATE`, `SECTION_BLOCKS_UPDATE` | EXCLUDED as controller observation |
| `SEM-020` entity observations | `ADD_ENTITY`, `MOVE_ENTITY`, `SET_ENTITY_DATA` | EXCLUDED |
| `SEM-021` inventory observations | `CONTAINER_SET_CONTENT`, `CONTAINER_SET_SLOT` | EXCLUDED |
| `SEM-022` world/chunk | `SET_TIME`, `LEVEL_CHUNK_WITH_LIGHT` | EXCLUDED |
| `SEM-023` presentation/UI | `SOUND`, `LEVEL_PARTICLES`, `OPEN_SCREEN` | CONTENT |
| `SEM-032` protocol | `BUNDLE`, `CHUNK_BATCH_*`, `START_CONFIGURATION` | PROTOCOL_MECHANISM |

The exclusion is deliberate: a packet being available to an ordinary player
client does not make it part of the robot's authoritative observation interface.
Swarmie's server-side `WorldQuery`/`LocalSensor` remain the intended observation
path. This inventory is a review baseline for the client information surface,
not proof of observation completeness.

## 6. Source inventory D — Fabric extension points

Parsed from the actual Fabric module JARs:

### 6.1 Custom payloads

`PayloadTypeRegistry` exposes registration methods only:

```text
serverboundPlay(), clientboundPlay(), serverboundConfiguration(),
clientboundConfiguration(), register(...), registerLarge(...)
```

There is **no public getter that enumerates declared payload types**.
`ServerPlayNetworking` exposes receiver discovery:

```text
registerGlobalReceiver(Type, handler)   // registration
getGlobalReceivers()                    // global receiver identifiers
registerReceiver(connection, Type, ...) // connection-specific
getReceived(ServerPlayer/connection)    // per-connection receiver ids
getSendable(ServerPlayer/connection)    // per-connection sendable ids
```

Therefore:

- declared types: not enumerable through these public APIs;
- global receivers: enumerable at runtime via `getGlobalReceivers()`;
- connection-specific receivers: enumerable only for a live connection via
  `getReceived`;
- handler bodies: not available offline; runtime instrumentation or mod
  cooperation is required.

The development world contains only Swarmie/Fabric API, so no third-party
payloads were present to observe at runtime.

### 6.2 Item storage and transfer

`ItemStorage` exposes two lookups, not a registry:

```text
ItemStorage.SIDED  : BlockApiLookup<Storage<ItemVariant>, Direction>
ItemStorage.ITEM   : ItemApiLookup<Storage<ItemVariant>, ContainerItemContext>
```

`BlockApiLookup.find(Level, BlockPos, Direction)` is position- and side-
dependent. There is **no global enumeration of storage instances**; only loaded
blocks/block entities can be probed. `Storage<T>` is a generic
insert/extract/iterate interface; concrete storages are content implementations.

This makes a generic transfer capability representable, but not globally
auditable from the JAR. The reviewed catalogue records it as `EXCLUDED` for the
current Adapter and a candidate for a future primitive (`SEM-031`).

## 7. Generated and reviewed artifacts

### 7.1 `docs/auditing/completeness.generated.json`

Generated by `tools/audit/ExtractAudit.java`. Structure:

```json
{
  "format": "swarmie-primitive-audit/1",
  "environment": { "minecraft": "26.3", "mapping": "none; ...", ... },
  "artifacts": [ { "role": "...", "version": "...", "sha256": "...", ... } ],
  "discovery": { "counts": { "serverbound_play": 93, ... } },
  "sources": [
    {
      "sourceId": "SB.PLAY.USE_ITEM_ON",
      "category": "serverbound_play",
      "kind": "packet",
      "class": "net.minecraft.network.protocol.game.ServerboundUseItemOnPacket",
      "handler": {
        "method": "handleUseItemOn",
        "implementation": "net.minecraft.server.network.ServerGamePacketListenerImpl",
        "calls": ["INVOKEVIRTUAL net.minecraft...ServerPlayerGameMode.useItemOn(...)"]
      },
      "fields": [ { "name": "hand", "type": "Lnet/minecraft/world/InteractionHand;" } ],
      "fingerprint": "99c52b6428f217f9...",
      "fingerprintKind": "handler_code_sha256"
    }
  ],
  "limitations": [ "..."]
}
```

Source IDs are stable for unchanged class names: `SB.PLAY.<PACKET>`,
`SB.COMMON.<PACKET>`, `SB.PLAY.<PACKET>.<FIELD>.<VALUE>`, `CB.PLAY.<PACKET>`,
`PAS.<Class>.<method>#<descriptor-hash>`, `EXT.FABRIC.<Class>.<method>#<hash>`.

Counts:

| Category | Entries |
|---|---:|
| serverbound play | 93 (58 packets + 35 variants) |
| serverbound common | 14 (6 packets + 8 variants) |
| clientbound play | 127 |
| passive embodied | 43 |
| Fabric extension | 67 |
| **total** | **344** |

Fingerprints are SHA-256 over the raw `Code` attribute plus method descriptor
for handlers/passive methods, and SHA-256 of the class file for clientbound and
Fabric entries. They are conservative review triggers, **not**
constant-pool-normalized semantic digests.

### 7.2 `docs/auditing/completeness.reviewed.md`

32 human-reviewed entries across all five dispositions. Example (`SEM-001`):

```text
## SEM-001
- title: Block destruction
- sources: SB.PLAY.PLAYER_ACTION, ...START_DESTROY_BLOCK, ...STOP_DESTROY_BLOCK ...
- disposition: MAPPED
- domain: blocks
- operation: destruction
- temporal: ACTION
- resources: selected tool/item in hand
- constraints: 6-block range; loaded chunk; native handleBlockBreakAction; ...
- rationale: Adapter MINE_BLOCK reaches native destroyBlock through the FakePlayer;
  start/stop/abort are the native lifecycle of one semantic primitive, not three.
- fingerprint: SB.PLAY.PLAYER_ACTION=099381ff9425af6b...
- dispatch: SB.PLAY.PLAYER_ACTION=ServerPlayerGameMode.handleBlockBreakAction
- status: accepted
```

Several source IDs intentionally map to one semantic entry. Variants are
covered by their parent packet unless a reviewer splits them. Entries that are
not reviewed are not invented; the validator reports them.

## 8. Validator and tests

`tools/audit/validate_completeness.py` implements:

| Change | Behavior |
|---|---|
| New source ID | `NEW_SOURCE` review finding when `--previous` is supplied |
| Disappeared source ID | `DISAPPEARED_SOURCE` error unless `removal: confirmed` |
| Fingerprint changed | `FINGERPRINT_CHANGED` review finding |
| Dispatch anchor changed | `DISPATCH_CHANGED` review finding |
| Reviewed entry references nonexistent source | `UNKNOWN_SOURCE` error |
| Unreviewed source | `UNREVIEWED_SOURCES` review finding |
| Two identical runs differ | `NONDETERMINISM` error (`--repeat` / test) |

It is **not** a CI gate. Structural errors exit 1; review findings exit 0
unless `--strict` is passed. The reviewed baseline is deliberately incomplete.

Fixture tests (`tools/audit/tests/test_validator.py`, 14 tests) cover clean,
unreviewed, invalid reference, new, disappeared, confirmed removal, fingerprint
change, dispatch change, missing required field, parent/variant coverage,
nondeterminism and strict CLI behavior.

## 9. Regenerate and verify

```powershell
# Regenerate from the local Loom/Gradle artifacts (no downloads)
.\tools\audit\regenerate.ps1

# Validate the reviewed catalogue (advisory by default)
python tools\audit\validate_completeness.py `
  --generated docs\auditing\completeness.generated.json `
  --reviewed docs\auditing\completeness.reviewed.md

# Validator fixture tests
python -m unittest discover -s tools\audit\tests -v

# Existing project tests
.\gradlew.bat test --offline
python -m unittest discover -s tools\sdk\tests -v
```

Determinism check:

```powershell
.\tools\audit\regenerate.ps1 -Out "$env:TEMP\audit-a.json"
.\tools\audit\regenerate.ps1 -Out "$env:TEMP\audit-b.json"
(Get-FileHash "$env:TEMP\audit-a.json").Hash -eq (Get-FileHash "$env:TEMP\audit-b.json").Hash
```

The two outputs were byte-identical on 2026-10-29
(`5E6A1DB1B696954F0A7C69C6B3D4B1B655B0A8A274F88EEFAC9392EF9D2E4508` for the
committed file at the time of writing).

## 10. Blind spots and known limitations

1. **No transitive call graph.** Only direct invocations in a method body are
   recorded. Reachability through framework dispatch is not computed.
2. **`invokedynamic` targets are unresolved.** Lambda targets (notably the
   `entityInside` visitor in `checkInsideBlocks`) are counted, not followed.
3. **Raw fingerprints.** Method code hashes are not constant-pool-normalized;
   recompilation can trigger review without a semantic change. Clientbound and
   Fabric entries use class hashes, which are coarser.
4. **Packet enumeration is class-based.** It covers top-level classes in
   `protocol/game` and `protocol/common` that implement `Packet`. It does not
   parse protocol registration order, aliases, configuration/login/status
   packets, or modded packets.
5. **Clientbound semantics are unreviewed** except for representative groups.
   Five clientbound classes have no handler method in the class.
6. **Passive coverage is bounded.** 11 seed classes and a method-name pattern;
   this is a justified starting point, not an exhaustive simulation map.
   Block-specific `entityInside` implementations and other content are not
   enumerated.
7. **No modded content.** The development environment has only Swarmie and
   Fabric API; third-party payloads, receivers, blocks, items, menus and
   storages are absent.
8. **No runtime instrumentation.** Fabric receiver handlers and per-connection
   registrations require a live server/connection; `getGlobalReceivers()` alone
   does not reveal handler bodies or declared-only payload types.
9. **No source-level proof.** Facts are bytecode-level; comments, annotations
   and intended semantics are human review.
10. **No completeness claim.** 198 of 344 generated sources are unreviewed by
    design.

## 11. What would be required for a maintained CI audit

1. Pin the five artifact hashes and the extractor/JDK version; fail on changes.
2. Keep the extractor deterministic (no timestamps, sorted output).
3. Reach an agreed reviewed coverage threshold before enabling a gate; until
   then run the validator in advisory mode.
4. Treat new/disappeared sources and fingerprint changes as review-required
   artifacts in the PR, not silent passes.
5. Add dispatch anchors for every reviewed semantic entry and review them when
   they change.
6. Establish a policy for unreviewed sources (allowlist with expiry, or
   per-category disposition defaults).
7. Add a runtime probe only in a disposable client for Fabric receivers and
   storage lookups; never in the production tick path.
8. Re-audit on every Minecraft/Fabric/Loom upgrade with a version-specific
   baseline.
9. Keep semantic dispositions human-owned; automation reports facts and
   requires review, it does not infer meaning.

## 12. Verified facts vs design proposals vs unverified

**Verified mechanically (from local class files):**

- package/class names, method descriptors, field types, enum constants;
- packet handler targets (`handle` invokeinterface) and implementation methods;
- direct invocation lists, branch counts, `invokedynamic` counts;
- method/class SHA-256 fingerprints;
- artifact sizes and SHA-256 values;
- Fabric API signatures listed above;
- deterministic repeat output.

**Design proposals / human judgments:**

- every `MAPPED`/`EXCLUDED`/`CONTENT`/`PRIVILEGED`/`PROTOCOL_MECHANISM`
  disposition in `completeness.reviewed.md`;
- the grouping of packet variants into one semantic primitive;
- clientbound observation exclusion under the embodied principle;
- the CI adoption path and coverage threshold.

**Unverified / not claimed:**

- completeness of the primitive catalogue;
- runtime behavior of third-party payloads, receivers or storages;
- a full passive simulation map;
- semantic meaning of all clientbound packets;
- call-graph reachability beyond direct invocations;
- behavior of a dedicated server, second client or modded environments for this
  audit.
