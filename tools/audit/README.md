# Primitive completeness audit tooling

This directory contains the feasibility prototype for the version-specific
primitive audit. It is **not** a completeness gate and is not wired into CI.

| File | Purpose |
|---|---|
| `ExtractAudit.java` | JDK 25 Class-File API extractor for packets, handlers, fields, direct calls, fingerprints and Fabric API signatures |
| `regenerate.ps1` | Rebuild `docs/auditing/completeness.generated.json` from local Loom/Gradle artifacts (no downloads) |
| `normalize_audit.py` | Assign roles, build default/final review units, map the reviewed catalogue and emit the normalization ledger |
| `validate_completeness.py` | Validate fingerprints/dispatch, plus candidate/evidence/unit/reference invariants of the normalized model |
| `tests/` | 33 fixture tests (validator rules and model invariants) |

## Commands

```powershell
.\tools\audit\regenerate.ps1

python tools\audit\normalize_audit.py

python tools\audit\validate_completeness.py `
  --generated docs\auditing\completeness.generated.json `
  --reviewed docs\auditing\completeness.reviewed.md

python -m unittest discover -s tools\audit\tests -v
```

`normalize_audit.py` writes `docs/auditing/completeness.normalized.json` and
`docs/auditing/completeness.normalization.json`. The validator reads the model
and ledger by default (siblings of the generated file); pass `--model` /
`--decisions` to override.

The validator exits 1 only for structural errors by default. Review findings
(new/unreviewed sources, fingerprint or dispatch changes, disappeared sources)
exit 0 unless `--strict` is passed. Read
`docs/auditing/round-0-report.md` and `docs/auditing/audit-model.md` before
enabling anything stricter. No completeness gate is wired into CI.
