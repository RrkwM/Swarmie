# Swarmie

[![CI](https://github.com/RrkwM/Swarmie/actions/workflows/ci.yml/badge.svg)](https://github.com/RrkwM/Swarmie/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/RrkwM/Swarmie)](https://github.com/RrkwM/Swarmie/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

> Programmable hovering robots for Minecraft 26.3 / Fabric, with a
> Minecraft-independent Adapter contract.

Repository: https://github.com/RrkwM/Swarmie  
Latest release: https://github.com/RrkwM/Swarmie/releases/tag/v0.1.0  
Wiki: https://github.com/RrkwM/Swarmie/wiki

## What it is

Swarmie adds a real Minecraft `Entity` with:

- server-authoritative three-axis hovering, native collision and persistence;
- a 9-slot robot inventory;
- native block/item interaction through a temporary FakePlayer;
- per-robot Runtime FIFO: timed flight, stop, mining, item-on-block;
- independent world query and local sensing;
- a pure Java `:contract` module with public Adapter submission, cancellation,
  results, lifecycle events, idempotency and batches;
- a bounded registry/event store and a default-off localhost JSONL development
  transport.

It is **not** an AI, pathfinding, task-orchestration, GUI-container or
remote-network project.

## Current status

| Item | Status |
|---|---|
| Version | `0.1.0` (pre-1.0) |
| Java | 25 required |
| Minecraft | 26.3 |
| Fabric Loader / API | 0.20.1 / 0.165.0+26.3 |
| CI | passing |
| JUnit | 43 passing |
| Release JAR | `swarmie-0.1.0.jar` |
| JAR SHA-256 | `fe3007e7df6ba694e6291149f49b50ae48cedabfa2946e56c42f9d0502a34f9a` |

The Adapter contract is `0.1` and still pre-stable.

## Install (players)

Requirements: Minecraft 26.3, Fabric Loader 0.20.1, Fabric API 0.165.0+26.3,
Java 25.

1. Download `swarmie-0.1.0.jar` from
   [Releases](https://github.com/RrkwM/Swarmie/releases/tag/v0.1.0).
2. Put it in `mods/` together with Fabric API.
3. Start the game with Java 25.

**0.1.0 is an admin/developer preview.** There is no crafting recipe, robot
item or spawn egg yet. An OP can create a robot with:

```mcfunction
/summon swarmie:swarmie ~ ~ ~
```

Then:

```mcfunction
/swarmie status @e[type=swarmie:swarmie,limit=1]
/swarmie fly @e[type=swarmie:swarmie] 0.1 0 0 20
/swarmie stop @e[type=swarmie:swarmie]
```

Full player instructions, commands and troubleshooting:
**[Player Guide](docs/player-guide.md)**.

## Build (developers)

Requires JDK 25 and Git. The first build downloads Gradle, Minecraft and Fabric
dependencies from the official repositories.

```bash
git clone https://github.com/RrkwM/Swarmie.git
cd Swarmie
./gradlew test
./gradlew build
./gradlew runClient
```

Windows:

```powershell
.\gradlew.bat test
.\gradlew.bat build
.\gradlew.bat runClient
```

Output:

```text
build/libs/swarmie-0.1.0.jar
```

Architecture, Adapter API, test strategy and extension boundaries:
**[Developer Guide](docs/developer-guide.md)**.

## Architecture

```text
Minecraft layer
  Entity / native movement, collision, blocks/items
        │
Runtime + capabilities
  FIFO, InteractionActor, WorldQuery, LocalSensor
        │
Adapter layer
  pure-Java contract, registry, events, inbound/outbound queues, transport
        │
Future external control plane
```

Hard rules:

- `contract/` must not depend on Minecraft/Fabric/Loom;
- Runtime owns scheduling, not world access;
- native block/item behavior stays native;
- external clients use Adapter contracts, not `SwarmieEntity`;
- debug commands are not public API.

## Documentation

| Audience / topic | Document |
|---|---|
| Player install, commands, lifecycle, troubleshooting | [`docs/player-guide.md`](docs/player-guide.md) |
| Developer setup, architecture, Adapter, testing | [`docs/developer-guide.md`](docs/developer-guide.md) |
| Contract fields, errors and serialization | [`docs/adapter-contract.md`](docs/adapter-contract.md) |
| Registry, lifecycle, serverEpoch, event ring | [`docs/adapter-registry.md`](docs/adapter-registry.md) |
| Inbound/outbound, idempotency, batch, cancel | [`docs/adapter-io.md`](docs/adapter-io.md) |
| Four commands: state, side effects, limits | [`docs/adapter-command-specs.md`](docs/adapter-command-specs.md) |
| Local JSONL/Python development transport | [`docs/local-transport.md`](docs/local-transport.md) |
| Semantic versioning and release process | [`docs/versioning.md`](docs/versioning.md) |
| Changelog | [`CHANGELOG.md`](CHANGELOG.md) |
| Test evidence matrix | [`docs/test-matrix.md`](docs/test-matrix.md) |
| Contribution rules | [`CONTRIBUTING.md`](CONTRIBUTING.md) |
| Maintainer handoff | [`HANDOFF.md`](HANDOFF.md) |
| Wiki source/mirror | [`docs/wiki/Home.md`](docs/wiki/Home.md) / [GitHub Wiki](https://github.com/RrkwM/Swarmie/wiki) |

## Versioning and releases

Swarmie follows [Semantic Versioning 2.0.0](https://semver.org/). Current
`0.1.0` is the first public pre-1.0 release. See
[`docs/versioning.md`](docs/versioning.md) for bump rules and the release
checklist.

Release assets include the JAR and its `.sha256`.

## Known limitations

- No survival acquisition yet: OP `/summon` is the current entry point.
- GUI containers (chest/furnace/hopper/...) are not supported by the FakePlayer
  path; an API success is not confirmed container side effects.
- Mining drops stay in the world; they are not auto-collected.
- Commands are interrupted on unload and do not resume after reload.
- Dedicated-server, second-client, LOS and several collision/scale boundaries
  remain unverified.
- The localhost JSONL transport is default-off, loopback-only and has no
  authentication/TLS/remote security model.
- The Adapter contract is 0.x and may change.

## License

MIT — see [`LICENSE`](LICENSE).
