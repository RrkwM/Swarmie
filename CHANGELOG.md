[English](CHANGELOG.md) | [简体中文](CHANGELOG.zh-CN.md)

# Changelog

All notable changes to Swarmie are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Craftable `swarmie:swarmie_spawn_egg` item (unreleased `0.1.1` line):
  - shaped recipe: four iron ingots, four redstone and one copper ingot;
  - shown in the vanilla Spawn Eggs creative tab;
  - using it creates a **new** robot with a fresh UUID and an empty nine-slot
    inventory; it does not capture, recall or restore an existing robot;
  - `/summon swarmie:swarmie` is unchanged.
- Item definition, model, 16×16 texture, `en_us`/`zh_cn` names and a
  recipe-unlock advancement.
- Data-contract JUnit coverage for the item definition, model, recipe,
  advancement and lang keys (46 tests total).

### Documentation

- Split the public README into a short landing page.
- Added canonical [Player Guide](docs/player-guide.md) and
  [Developer Guide](docs/developer-guide.md).
- Published GitHub Wiki navigation, player/developer summaries, installation
  and FAQ pages.
- Added English-default bilingual documentation; Simplified Chinese versions use
  `.zh-CN` files and `-zh-CN` Wiki pages.
- Anonymized maintainer-local absolute paths in the public tree.

### Verified

- 46 JUnit tests (`test --rerun-tasks`).
- Real client (Minecraft 26.3, disposable world):
  - the egg is given as `Swarmie Spawn Egg` and is found in creative search;
  - `/recipe give` unlocks the recipe; the recipe book auto-fills the 3×3
    iron/redstone/copper grid and the output crafts the egg;
  - right-clicking the egg logs a fresh `ROBOT_LOADED`, and
    `/swarmie inventory show` reports an empty inventory;
  - creative mode does not consume the egg (vanilla spawn-egg behavior).
- Still unverified: dedicated-server startup, second-client sync and the
  remaining collision boundaries.

## [0.1.0] - 2026-10-28

First public release. This is a pre-1.0 release: the public Adapter contract
and command semantics are not yet stable.

### Added

- Real Minecraft `26.3` Fabric `SwarmieEntity` with server-authoritative
  three-axis hovering, native collision, persistence, and a 9-slot inventory.
- Per-robot Runtime FIFO with timed flight, stop, mining, and item-on-block
  commands.
- Native interaction through a temporary `FakePlayer`; no fake player body is
  added to the world.
- Independent `WorldQuery` and `LocalSensor` capabilities.
- Pure Java `:contract` subproject (`0.1` pre-stable) with four command specs,
  acknowledgements, results, events, cancellation, and batch contracts.
- Robot registry with `LOADED`/`UNLOADED`/`REMOVED`, last-known metadata, and
  bounded removed records.
- Session `serverEpoch`, bounded 1024-event ring, sequence-gap detection
  (`missed`), and command lifecycle events.
- Bounded inbound queue, per-tick budget, correlation idempotency (capacity
  1024, terminal TTL 600 s), batch submission, and backpressure errors.
- Public `cancel(robotId, correlationId)` with active/queued/terminal/unknown
  handling.
- Default-off, loopback-only JSONL development transport and dependency-free
  Python client.
- JUnit tests, contract boundary check, GitHub Actions CI, CONTRIBUTING,
  semantic-versioning policy and handoff documentation.

### Verified

- 43 JUnit tests.
- Real client: load/unload/reload, chunk unload, dimension change, player
  removal, command interruption, Adapter submit/duplicate/conflict, active
  cancel, Python mine/fly/stop/cancel/reconnect, stone placement and inventory
  consumption.
- Final public build: `swarmie-0.1.0.jar`; SHA-256 is published on the GitHub
  Release.

### Known limitations

- GUI containers are not supported with the current FakePlayer path; an API
  success must not be treated as confirmed container side effects.
- Event-ring overflow has only been exercised with a small-capacity JUnit
  store, not a filled 1024-event live session.
- Batch submission and event-ring overflow lack live commands.
- Second-client sync, dedicated-server startup, LOS sensing, and some
  high-speed/diagonal/stair/ceiling collision cases are unverified.
- The localhost JSONL transport has no authentication, TLS, remote binding, or
  multi-user security model.

