English | [简体中文](Developer-Guide-zh-CN)

# Developer Guide

> Short Wiki mirror. Canonical developer docs:
> [docs/developer-guide.md](https://github.com/RrkwM/Swarmie/blob/main/docs/developer-guide.md).

## Requirements

JDK **25**, Git, and network access for the first Gradle/Minecraft/Fabric
dependency download.

## Build and test

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

The output is `build/libs/swarmie-0.1.0.jar`.

## Layout

- `contract/` — pure Java contract; no Minecraft/Fabric imports.
- `src/main/java/dev/swarmie/` — Entity, Runtime, interaction, query, sensor,
  commands and Adapter.
- `src/main/java/dev/swarmie/adapter/` — registry, events, inbound/outbound
  queues, localhost JSONL.
- `src/test/` and `contract/src/test/` — JUnit.
- `docs/` — specs, test matrix and handoff.

## Adapter

```java
SubmitAck ack = SwarmieAdapter.submit(
        robotId,
        new CommandSpec.Stop(),
        "correlation-id");
```

- `ACCEPTED` is not execution completion; observe events/final result.
- `cancel` is asynchronous; terminal state is `COMMAND_CANCELLED`.
- Event batches expose `serverEpoch`, `sequence`, `missed`.
- Server tick owns entity/Runtime access; external threads only enqueue.
- Event listeners must be fast and non-blocking.

Read next:

- [Contract](https://github.com/RrkwM/Swarmie/blob/main/docs/adapter-contract.md)
- [Registry/lifecycle](https://github.com/RrkwM/Swarmie/blob/main/docs/adapter-registry.md)
- [I/O and idempotency](https://github.com/RrkwM/Swarmie/blob/main/docs/adapter-io.md)
- [Command specs](https://github.com/RrkwM/Swarmie/blob/main/docs/adapter-command-specs.md)
- [Versioning](https://github.com/RrkwM/Swarmie/blob/main/docs/versioning.md)
- [Test matrix](https://github.com/RrkwM/Swarmie/blob/main/docs/test-matrix.md)

