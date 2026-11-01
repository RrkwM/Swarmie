English | [简体中文](Home-zh-CN)

# Swarmie

Programmable hovering robots for **Minecraft 26.3 / Fabric**.

- Current release: **0.1.0** (pre-1.0); `main` is `0.1.1-SNAPSHOT`
- License: MIT
- Repository: https://github.com/RrkwM/Swarmie
- Releases: https://github.com/RrkwM/Swarmie/releases

## Start here

- [Player Guide](Player-Guide) — install and use the current admin preview
- [Installation](Installation) — Fabric, Java 25 and the release JAR
- [Developer Guide](Developer-Guide) — build, architecture and Adapter
- [Python SDK](https://github.com/RrkwM/Swarmie/blob/main/docs/python-sdk.md) —
  typed client for the local JSONL transport
- [FAQ](FAQ) — common failures and limitations

## Current scope

Swarmie provides a real Minecraft `Entity` with server-authoritative flight, a
9-slot inventory, native interaction, per-robot Runtime commands, world query,
local sensing, a Minecraft-independent Adapter contract, and a standard-library
Python SDK for the optional localhost transport.

0.1.0 has no robot item; `main` adds a craftable spawn egg for the unreleased
0.1.1. Current 0.1.0 is an admin/developer preview: summon a robot with
`/summon swarmie:swarmie`. The spawn egg creates a new robot only; it does not
capture or recall an existing one.

There is no AI, pathfinding, GUI-container support, remote network service, or
multi-robot scheduler.

## Canonical documentation

This Wiki is a short navigation/mirror. Versioned canonical docs live in the
repository:

- https://github.com/RrkwM/Swarmie/blob/main/README.md
- https://github.com/RrkwM/Swarmie/blob/main/docs/player-guide.md
- https://github.com/RrkwM/Swarmie/blob/main/docs/developer-guide.md
- https://github.com/RrkwM/Swarmie/blob/main/docs/python-sdk.md
- https://github.com/RrkwM/Swarmie/blob/main/CHANGELOG.md

## Known limitations

- GUI containers are not supported by the FakePlayer path.
- Mining drops stay in the world; they are not auto-collected.
- The localhost JSONL transport is default-off and developer-only; the Python
  SDK has no authentication/TLS and is not a remote API.
- Dedicated-server, second-client, LOS, and some collision boundaries are
  unverified.
- The Adapter contract is 0.x and may change.

