# Round 0 Report — Audit Model Normalization

> Status: Round 0 methodology **complete**; the semantic catalogue is
> intentionally **PARTIAL**. 157 normalized review units remain `PENDING`.
>
> Branch: `audit/primitive-completeness`. No runtime, contract, command or
> Python SDK behavior was changed.

## 1. What changed

Round 0 separates facts, roles, review units, dispositions, review status and
GAPs:

- record roles are assigned by deterministic source-family/kind rules;
- action/`containerInput` values become separate provisional candidates while
  the packet envelope becomes evidence;
- the reviewed catalogue is mapped onto default units, and every merge/group is
  recorded in a machine-readable ledger;
- review status (`REVIEWED`/`PENDING`) is separate from disposition;
- pending units carry `reason`, `blocker` and only a provisional `proposal`;
- the validator enforces candidate coverage, evidence accountability,
  review-unit accountability and referential integrity;
- the blanket clientbound `EXCLUDED` conclusion is withdrawn; the affected
  units are `PENDING` for the Round 3 observation review.

See [audit-model.md](audit-model.md) for definitions, role rules, invariants,
dispatch boundary, blind-review format and the two deferred extractor
improvements.

## 2. Before / after

| Measure | Before (handoff) | After normalization |
|---|---:|---:|
| Generated source records | 344 | 344 |
| Derived evidence records | 0 | 234 |
| Model source records | 344 | **578** |
| Candidate records | not modelled | **270** |
| Evidence records | not modelled | **241** |
| Extension-surface records | not modelled | **67** |
| Default review units | not modelled | **270** |
| Final review units | 32 reviewed entries (table rows) | **178** |
| `REVIEWED` units | 22 accepted entries (10 provisional) | **21** |
| `PENDING` units | not modelled | **157** |
| Candidate coverage | not enforced | **270/270** |
| Evidence coverage | not enforced | **241/241** |
| Evidence orphans | not modelled | **0** |
| GAP findings | not modelled | **0** |

“32 reviewed entries (table rows)” is not the same as 32 semantic units: a
reviewed entry can merge several default units, and an entry can be `PENDING`
rather than `REVIEWED`.

## 3. Source records by role

| Role | Count | Composition |
|---|---:|---|
| `candidate` | 270 | 87 serverbound play (52 packets + 35 action variants), 13 common (5 packets + 8 variants), 127 clientbound, 43 passive |
| `evidence` | 241 | 7 packet envelopes + 64 handler facts + 43 passive method facts + 127 clientbound class facts |
| `extension_surface` | 67 | Fabric method/field signatures |
| **total** | **578** | 344 generated + 234 derived evidence |

The 344 generated records are unchanged from the audit prototype. The 234
derived evidence records make handler methods, calls and fingerprints
independently traceable instead of embedding them ambiguously in packet rows.

## 4. Review units by family and status

| Family | Units | `REVIEWED` | `PENDING` |
|---|---:|---:|---:|
| `serverbound_play` | 36 | 13 | 23 |
| `serverbound_common` | 2 | 1 | 1 |
| `clientbound_play` | 104 | 1 | 103 |
| `passive_embodied` | 33 | 4 | 29 |
| `fabric_extension` | 3 | 2 | 1 |
| **total** | **178** | **21** | **157** |

The 270 default units (one per candidate) reduce to 178 final units because
reviewed entries explicitly merge sources. There are 21 `MERGE` decisions,
3 `GROUP` decisions for extension capability groups, 1 `SPLIT` rule, 2
`RECLASSIFY` decisions, 1 `SCOPE` decision and 1 `RULE` decision: **29**
normalization decisions in
[completeness.normalization.json](completeness.normalization.json).

Every candidate is linked to exactly one unit; every evidence record is
referenced; 56 of 67 extension surfaces are scope-excluded with a reason and
the `ND-SCOPE-EXT` decision (the other 11 are covered by the three extension
units).

## 5. The former “198 unreviewed source IDs”

The old validator computed:

- generated sources: 344
- directly cited by reviewed entries: 109
- covered after parent-packet expansion: 146
- uncovered: **198**

Normalization shows that 198 is **not** 198 semantic decisions. Breaking down
the 198 uncovered source IDs by normalized role:

| Role of the 198 | Count | Consequence |
|---|---:|---|
| candidate | 141 | 141 pending candidate units |
| extension_surface | 56 | scope-excluded (`ND-SCOPE-EXT`), not semantic units |
| packet envelope evidence | 1 | supports the pending `SEEN_ADVANCEMENTS` variants |
| **total** | **198** | |

The old parent-expansion rule also hid five `PlayerAction` variants:
`DROP_ALL_ITEMS`, `DROP_ITEM`, `RELEASE_USE_ITEM`, `STAB` and
`SWAP_ITEM_WITH_OFFHAND` were treated as covered because the envelope
`SB.PLAY.PLAYER_ACTION` was cited, while the reviewed entry only decided
`START/STOP/ABORT/CHANGE_DESTROY_BLOCK`. They are now five explicit pending
units.

Therefore:

```text
157 pending units
  = 141 pending units from the former uncovered candidate sources
  + 11 pending units that correspond to PENDING reviewed entries
  +  5 previously hidden PlayerAction variants
```

The 11 pending reviewed units are `SEM-003`, `SEM-004`, `SEM-005`, `SEM-007`,
`SEM-019`, `SEM-020`, `SEM-021`, `SEM-022`, `SEM-023`, `SEM-026`, `SEM-031`.
The 5 hidden variants are the concrete case where the old count understated
semantic work. That is the central result of Round 0: **178 genuine review
units**, not 198 rows and not 32 entries.

## 6. Clientbound scope

The previous blanket `EXCLUDED` conclusion for clientbound observations is
withdrawn by `ND-CB-WITHDRAW`.

- 127 clientbound candidates, 104 units after the five reviewed merges;
- 103 units are `PENDING` for Round 3;
- 1 unit remains `REVIEWED` as `PROTOCOL_MECHANISM`: `SEM-032`
  (`BUNDLE`, `CHUNK_BATCH_*`, `START_CONFIGURATION`), which is delivery
  mechanics rather than an observation claim;
- `SEM-019`–`SEM-023` are `PENDING` with a Round 3 blocker; their previous
  `EXCLUDED`/`CONTENT` outcomes are retained only as `proposal`;
- no clientbound consumer or runtime behavior was added.

`PROTOCOL_MECHANISM` for batching is not a judgment that observation packets
are irrelevant; it is a narrow statement about the batching/configuration
delivery mechanism.

## 7. GAP findings

**0 GAPs.** No pending unit has reached a justified conclusion that a required
public Adapter behaviour is missing. Attack/interaction/air-item-use/slot/
pickup/storage remain `PENDING` with proposals; turning a proposal into a GAP
requires a semantic review and an explicit "required public behaviour" case.
The GAP schema and validator checks exist and are fixture-tested, but no gap was
manufactured for this round.

## 8. Invariants and tests

`validate_completeness.py` now checks:

- A: every candidate is in a unit or explicitly scope-excluded;
- B: every evidence record is referenced or has an orphan reason;
- C: `REVIEWED` has disposition/rationale; `PENDING` has reason/blocker and no
  final disposition; GAPs reference real units and have all required fields;
- D: source/unit references and normalization decisions exist, IDs are unique,
  roles and dispositions do not conflict, counts are recomputed and model
  hashes are current.

Fixture tests (`tools/audit/tests/test_model.py`, 19 tests) cover role
assignment, normalization, candidate omission, evidence orphaning, pending/
reviewed validity, GAPs, decision references, conflicting dispositions, role
conflicts, extension omissions, stale counts and stale hashes.

Existing validator fixture tests remain: 14; total audit tests: **33**.

## 9. Verification and reproducibility

| Command | Result |
|---|---|
| `.\tools\audit\regenerate.ps1` (twice) | byte-identical generated output |
| `python tools\audit\normalize_audit.py` | 578 sources; 178 units |
| `python tools\audit\validate_completeness.py --generated docs\auditing\completeness.generated.json --reviewed docs\auditing\completeness.reviewed.md` | `errors=0`, `review_findings=0` |
| `python -m unittest discover -s tools\audit\tests -v` | 33 passed |
| `.\gradlew.bat test --offline --rerun-tasks` | 46 JUnit passed |
| `python -m unittest discover -s tools\sdk\tests -v` | 44 passed |

Hashes:

```text
completeness.generated.json     5e6a1db1b696954f0a7c69c6b3d4b1b655b0a8a274f88eefac9392ef9d2e4508
completeness.reviewed.md        9a18bcf90c96ad136b222ba86aefaa8dc7e995b1329cc0456b834e811d80f743
completeness.normalized.json    a384784213e638ab5206e0066df82f33e850f8e0e646083c7296275af0de4dea
completeness.normalization.json 47b687e240dec3af86a5d0dafa1b7811678bf96ffdbb4265964e81e233be3008
```

The normalizer is deterministic: sources and units are sorted, hashes and
counts are computed, and no timestamps are written. The model stores the
generated and reviewed catalogue hashes; the validator fails with `STALE_MODEL`
if either changes without renormalization, and with `STALE_COUNTS` if the counts
no longer match the model.

## 10. Remaining limitations and blockers

- **157 pending units** are the honest remaining semantic work:
  38 serverbound play/common units (24 pending: 23 play + 1 common), 103
  clientbound, 29 passive, 1 extension.
- Clientbound pending is blocked on the Round 3 observation/self-state review.
  Presentation/UI packets may still be excluded after review; they were not
  prejudged.
- Passive pending is blocked on the Round 2 passive-embodiment review, including
  the lambda/`entityInside` blind spot.
- Active-ingress pending is blocked on Round 1/2 scope decisions (attack,
  interaction, air use, slot selection).
- The one extension pending unit (`ServerConfigurationNetworking`) is blocked on
  the extension-surface scope review.
- 56 extension signatures are an inventory, not semantic units; their scope is
  recorded, not silently dropped.
- `invokedynamic`/lambda targets remain unresolved; normalized fingerprints
  remain a proposal. Both are documented in `audit-model.md` §7 and are not
  implemented in Round 0.
- No runtime probes, no modded content, no transitive call graph, no semantic
  completeness claim.
- The validator remains advisory; it is not a CI gate.

## 11. Why the counts are trustworthy

- Source facts are generated by the JDK 25 Class-File API from the local 26.3
  and Fabric JARs; the generated file is not hand-edited.
- Role assignment and default splitting are deterministic rules over source
  family/kind, not per-row guesses.
- Every reviewed merge/group is recorded in the ledger; source identities are
  preserved in units.
- The model validator recomputes all counts and fails on stale hashes; fixture
  tests mutate the model to prove each invariant fails when violated.
- Regeneration is byte-reproducible for unchanged inputs.
- The remaining uncertainty is semantic (dispositions/pending decisions), not
  arithmetic or traceability.

## 12. Files changed

New/changed under `docs/auditing/`:

- `completeness.normalized.json` — normalized sources, default/final units,
  scope exclusions, counts.
- `completeness.normalization.json` — 29 normalization decisions.
- `audit-model.md` — definitions, role rules, invariants, dispatch rule,
  extractor proposals, blind-review format.
- `round-0-report.md` — this report.
- `completeness.reviewed.md` — pending entries now use `PENDING` + proposal +
  reason/blocker; clientbound exclusions withdrawn.
- `primitive-completeness-feasibility.md` — Round 0 notice pointing here.

Tooling:

- `tools/audit/normalize_audit.py` — normalizer/ledger generator.
- `tools/audit/validate_completeness.py` — catalogue + model invariants.
- `tools/audit/tests/test_model.py` — new model fixtures.
- `tools/audit/README.md` — updated commands.
