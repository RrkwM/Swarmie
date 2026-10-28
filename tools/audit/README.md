# Primitive completeness audit tooling

This directory contains the feasibility prototype for the version-specific
primitive audit. It is **not** a completeness gate and is not wired into CI.

| File | Purpose |
|---|---|
| `ExtractAudit.java` | JDK 25 Class-File API extractor for packets, handlers, fields, direct calls, fingerprints and Fabric API signatures |
| `regenerate.ps1` | Rebuild `docs/auditing/completeness.generated.json` from local Loom/Gradle artifacts (no downloads) |
| `validate_completeness.py` | Compare generated sources with the reviewed catalogue; report new/disappeared/hash/dispatch/unreviewed findings |
| `tests/test_validator.py` | Fixture tests for every validator rule |

## Commands

```powershell
.\tools\audit\regenerate.ps1

python tools\audit\validate_completeness.py `
  --generated docs\auditing\completeness.generated.json `
  --reviewed docs\auditing\completeness.reviewed.md

python -m unittest discover -s tools\audit\tests -v
```

The validator exits 1 only for structural errors by default. Review findings
(new/unreviewed sources, fingerprint or dispatch changes, disappeared sources)
exit 0 unless `--strict` is passed. Read the feasibility report at
`docs/auditing/primitive-completeness-feasibility.md` before enabling anything
stricter.

`regenerate.ps1` uses the local JDK and the local `tools/mc` / Gradle caches by
default. Pass `-ServerJar`, `-ClientJar`, `-FabricNetJar`,
`-FabricTransferJar`, `-FabricLookupJar` and `-Out` to point at equivalent
artifacts; the script never downloads dependencies.
