# Contributing to Swarmie

## Before you start

- Minecraft `26.3`, Fabric Loader `0.20.1`, Fabric API `0.165.0+26.3`.
- JDK **25** (Temurin recommended).
- First build needs access to the official Fabric/Maven repositories; no
  project-local toolchain is required.
- Do not commit `tools/jdk`, `tools/repo`, `tools/gradle-home`, `run/`, or
  `build/`. They are machine-local.

## Local development

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

The output JAR is `build/libs/swarmie-0.1.0-SNAPSHOT.jar`.

## Project boundaries

- `contract/` is a pure Java subproject. It must not gain Minecraft/Fabric
  imports or dependencies.
- `src/main/java/dev/swarmie/` contains Minecraft/Fabric implementation.
- `src/test/` contains JUnit tests; `contract/src/test/` belongs to the
  contract module.
- Adapter contracts, lifecycle/event semantics, and command specs are documented
  under `docs/`. Update the relevant spec before changing public behavior.

## Testing

```bash
./gradlew test
```

Expected: 43 JUnit tests on the current checkpoint. The test matrix is in
`docs/test-matrix.md`; it distinguishes automated, real-client, static, and
unverified evidence. Do not report a JUnit pass as a real-client pass.

For a quick client world, the maintainer-only property is:

```bash
./gradlew runClient -PswarmieQuickPlay="New World"
```

## Code and review

- Preserve existing motion/collision/inventory/interaction/query semantics
  unless the change explicitly includes a tested replacement.
- Keep Runtime responsible for scheduling, not world access.
- Never use a test-only game command as the production API.
- Keep local language/transport defaults safe: the JSONL transport is
  default-off, loopback-only, and is not a remote/security model.
- Include a test for new behavior, or document why only manual verification is
  possible.
- Keep PRs focused; do not mix format changes with behavioral changes.

## License

This project is licensed under the MIT License; see `LICENSE`. By contributing,
you agree that your contribution is provided under the same MIT terms.
