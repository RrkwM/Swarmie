# Versioning Policy

Swarmie follows [Semantic Versioning 2.0.0](https://semver.org/).

## Current line

```text
0.1.0
```

`0.y.z` is initial development: the public Adapter contract and command
semantics are still allowed to change. There is no 1.0 compatibility promise
yet.

The Adapter contract has its own `ContractVersion` (`0.1`). It is versioned
independently from the Minecraft mod version; a contract change must be called
out in the changelog and in the contract documentation.

## Version rules

### Before 1.0

| Change | Version |
|---|---|
| Breaking public API/contract/command semantics | bump `MINOR` (`0.y.0`) |
| Backward-compatible capability or behavior addition | bump `PATCH` (`0.y.z+1`) |
| Bug fix | bump `PATCH` |
| Internal refactor with no public behavior change | no version bump required |

Because `0.x` is explicitly unstable, consumers must pin an exact release and
read the changelog before upgrading.

### From 1.0

| Change | Version |
|---|---|
| Breaking public API | `MAJOR` |
| Backward-compatible feature | `MINOR` |
| Backward-compatible fix | `PATCH` |

Pre-releases use `-alpha.N`, `-beta.N`, or `-rc.N`. Released artifacts must not
contain `SNAPSHOT`; development builds may use `-SNAPSHOT`.

## Sources of truth

| Item | Source |
|---|---|
| Mod version | `gradle.properties` → `mod_version` |
| Contract version | `ContractVersion.CURRENT` |
| Minecraft/Fabric versions | `gradle/libs.versions.toml` |
| Release notes | `CHANGELOG.md` + GitHub Release |
| Built artifact | `build/libs/swarmie-<mod_version>.jar` |

## Release checklist

1. Update `mod_version` to the release version (no `-SNAPSHOT`).
2. Add a dated `CHANGELOG.md` entry with breaking changes and known limits.
3. Run `./gradlew test build` and the representative real-client checks.
4. Confirm contract boundary and JAR content.
5. Commit, push `main`, and wait for GitHub Actions success.
6. Create annotated tag `vX.Y.Z`.
7. Create GitHub Release from the tag; attach the built JAR and its SHA-256.
8. Mark `0.y.z` releases as pre-release until the contract is stable.
9. Bump `mod_version` to the next `-SNAPSHOT` only after the release is
   published.

## Compatibility matrix

The mod targets:

```text
Minecraft 26.3
Fabric Loader 0.20.1
Fabric API 0.165.0+26.3
Java 25
```

Changing any of these is a compatibility event and must be documented even if
the Swarmie API itself does not change.
