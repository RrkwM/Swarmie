# Provisioning bootstrap (P1-T1c)

This directory contains files used **only** by `tools/scripts/provision.ps1`.

- `online-repos.init.gradle.kts` — Gradle init script that adds the official
  remote repositories (`maven.fabricmc.net`, Gradle Plugin Portal, Maven Central)
  for the first online resolution.

Rules:

1. Normal builds (`gradlew`, `tools/scripts/build.ps1`) never pass this init
   script. `settings.gradle.kts` keeps only the local `tools/repo` file
   repository.
2. The init script must not be copied into `settings.gradle.kts`,
   `build.gradle.kts`, or `gradle.properties`.
3. Any new host added here must be justified in
   `docs/provisioning-findings.md` and be an official source for the pinned
   versions.
