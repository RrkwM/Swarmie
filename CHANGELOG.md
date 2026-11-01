[English](CHANGELOG.md) | [简体中文](CHANGELOG.zh-CN.md)

# Changelog

All notable changes to Swarmie are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
[Semantic Versioning](https://semver.org/).

## [0.1.1] - 2026-11-02

Pre-1.0 feature release. The Adapter contract remains `0.1`; the new contract
types and JSONL operations are backward-compatible additions.

### Added

- API v1 self-state: position, native velocity, yaw/pitch, dimension, lifecycle,
  `serverEpoch` and `serverTick` freshness.
- Persistent velocity setpoint channel: latest-wins updates, revision,
  `0.35` blocks/tick clamp, clearing/deceleration and exclusive ownership with
  legacy `TIMED_FLIGHT`/`STOP`.
- Nine-slot ordered inventory snapshots, selected-slot reporting and a stable
  tick boundary snapshot.
- Explicit single-block observation with `OK`, `UNLOADED`, `OUT_OF_BOUNDS`,
  `INVALID`, `UNSUPPORTED` and `ERROR`; unavailable positions are not reported
  as air.
- Standard-library Python SDK (`tools/sdk/swarmie`): exact-UUID robots,
  discovery, self-state, inventory, block observation, setpoints, action
  handles, cancellation, events, epoch/gap-aware reconnect and structured
  errors.
- Craftable `swarmie:swarmie_spawn_egg` (four iron ingots, four redstone, one
  copper ingot), recipe unlock, item model/texture and `en_us`/`zh_cn` names.
  Using it creates a new robot with a fresh UUID and empty inventory.
- `/swarmie help` with `flight`, `actions`, `inventory`, `query`, `adapter` and
  `lifecycle` topics, explicitly marked as OP-level debug commands.
- `docs/api-current.md`: a pre-stable quick reference for the currently
  implemented Python/JSONL surface.
- Verified minimal farming-preparation workflow: inspect selected inventory,
  observe farmland, use a wheat seed, then re-read inventory and the crop block.
- Test-only DCPU-16/MMIO bridge spike:
  - pure-Java `SET`/`IAS`/`RFI` subset;
  - motion and clock MMIO devices;
  - player guest → MMIO → existing `SwarmieRuntime` → Entity movement;
  - terminal status, one guest interrupt, ACK and cleanup;
  - remote setpoint/legacy motion rejected while the guest owns motion.
- Draft PCI-inspired SDB v0 device-bus specification:
  configuration space, slots, BARs, capabilities, IRQ control, lifecycle,
  fault and player-driver responsibilities. No SDB implementation yet.
- New white/cyan robot model with a dark face screen, four independent idle
  wings, a visual hover bob and a 0.45 × 0.65 hitbox (2×2 robots per block).

### Changed

- Entity position updates now use `updateInterval(1)` instead of three ticks,
  removing visible low-speed position stepping.
- Documentation refreshed for the current API, help commands, test counts and
  the distinction between public, experimental and internal capabilities.
- The legacy `LocalSensor` forward-scanning capability is documented as an
  internal/debug capability, not a public Sensor API and not part of SDB.

### Verified

- 75 Java main tests and 12 contract tests (`test --rerun-tasks`).
- 66 Python SDK/mock-server tests.
- 33 audit tests; completeness validator reports `errors=0` and
  `review_findings=0`.
- Real integrated-server checks: lifecycle, state/setpoints, inventory and
  block observation, farming preparation, DCPU timed flight/terminal IRQ/ACK,
  remote-motion exclusion and cleanup.
- Release JAR: `swarmie-0.1.1.jar`, 238,293 bytes, SHA-256
  `0913341d63e587200d5bbccc91e1309e4ee7297e36abaa31ece56d60490ed031`.

### Known limitations

- DCPU support is an instruction-budget subset, not a complete DCPU-16
  implementation; there is no assembler, cartridge, persistence, NIC or UI.
- SDB v0 is a draft specification only; no device bus, block-probe device or
  third-party provider is implemented.
- `LocalSensor` is internal/debug; it is not a player-defined sensor system.
- No general farming, pathfinding, task scheduling, orientation setpoint or
  public region/entity/sensor observation.
- GUI containers, inventory transfer and item components are not supported.
- Dedicated-server startup, second-client synchronization and some collision
  boundaries remain unverified.
- The localhost JSONL transport has no authentication, TLS or remote binding.
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

