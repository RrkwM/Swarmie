# Audit Model

> Status: Round 0 (2026-10-29). This document defines the normalized model used
> by the primitive-completeness audit. It does not claim catalogue completeness.

## 1. Five distinct concepts

**Source record** — a mechanically discovered fact: a packet, an explicit
discriminator value, a packet envelope, a handler method/handler facts, a
direct invocation, a method/class fingerprint, a passive-embodied hook, or a
Fabric API signature. A source record is evidence or a candidate source, not a
primitive.

**Review unit** — the smallest unit on which a semantic disposition must be
decided. It references zero or more candidate source records and the evidence
supporting them. A review unit is not a Java method or a JSON row.

**Semantic primitive** — a reviewed, reusable Swarmie capability with explicit
operation semantics, temporal semantics, constraints and observable outcomes.
One primitive may cover multiple review units; one review unit may later map to
multiple primitives. Round 0 establishes units only.

**Disposition** — the reviewed classification of a unit:
`MAPPED`, `PRIVILEGED`, `EXCLUDED`, `CONTENT`, `PROTOCOL_MECHANISM`.

**Evidence** — extracted source locations, handler/dispatch relationships,
bytecode facts, tests or reproducible experiments. Evidence records are
independently traceable and are not conclusions.

## 2. Review status is not disposition

Every review unit has exactly one review status:

| Status | Meaning | Required fields |
|---|---|---|
| `PENDING` | no justified final decision yet | `reason`, `blocker`; no final `disposition` |
| `REVIEWED` | a semantic decision is recorded | `disposition`, `rationale`, evidence |

A provisional outcome for a pending unit may be stored in `proposal` and must
be labelled as provisional. It is not a disposition.

A `PENDING` unit must not carry a final disposition. A completed review round
must have no in-scope `PENDING` units; otherwise the round is `PARTIAL` and
every pending unit and blocker is reported.

**GAP** is a third concept, not a status and not a synonym for `PENDING`. A GAP
is a reviewed finding that the required public Adapter behaviour is not
represented by an existing primitive. It must state:

- the behaviour that appears to be required;
- why it belongs in the public Adapter rather than `PRIVILEGED`, `EXCLUDED` or
  `CONTENT`;
- why existing primitives cannot express it;
- supporting evidence;
- the affected scenario, when known.

GAPs are design findings, not implementation instructions. Round 0 reports
**0 GAPs**: no pending unit has reached a justified "required public behaviour
is missing" conclusion. Attack, interaction, air item use, slot selection and
storage remain `PENDING` with proposals, not GAPs.

## 3. Record roles

Role assignment is deterministic by source family and record kind. It does not
use Java class/method names to infer semantics.

| Record | Role | Rule |
|---|---|---|
| Serverbound packet without discriminator | `candidate` | one unit per packet |
| Serverbound packet with `action`/`containerInput` discriminator | `evidence` (`packet_envelope`) | the envelope explains the variants; each discriminator value is a candidate |
| Serverbound discriminator value | `candidate` | unit `(packetType, enumValue)` |
| Clientbound packet | `candidate` | Round 3 observation review; not yet a primitive |
| Passive embodied method fact | `candidate` | bounded 11-class seed, method-name pattern |
| Handler method/signature/calls/fingerprint | `evidence` | derived `EV.HANDLER.<packet>` |
| Passive method calls/fingerprint | `evidence` | derived `EV.METHOD.<method>` |
| Clientbound class hash/fields | `evidence` | derived `EV.CLASS.<packet>` |
| Fabric API signature | `extension_surface` | inventory, not a candidate; only cited capability groups become units |
| Missing seed class | `evidence` (`missing_seed`) | reported, not silently dropped |

The generated packet record and its derived handler evidence are separate
source records. A fact serving multiple purposes (for example a packet record
that also carries a fingerprint) is represented by separate relationships
rather than an ambiguous single role.

## 4. Default normalization rules

1. A packet with an explicit `action`/`containerInput` enum produces one
   provisional candidate unit per discriminator value.
2. A field like `hand` or `usingSecondaryAction` is a candidate split
   dimension, but it does **not** automatically produce separate units. Any
   split/merge decision must be recorded.
3. A packet without a suitable discriminator produces one candidate unit with a
   stable source-derived ID (`U.<sourceId>`).
4. Existing public primitives are not used to group sources. Grouping comes
   from reviewed entries and is recorded in the ledger.
5. A reviewed entry may merge default units. The merged unit preserves all
   candidate and evidence source identities; no source is erased.
6. If a reviewed entry cites a packet envelope plus explicit variants, only
   those variants are covered. The envelope alone covers all its children.
   This rule prevents parent citation from silently marking unreviewed variants
   as reviewed.

Every non-trivial split/merge/grouping is recorded in
`docs/auditing/completeness.normalization.json` with a stable decision ID,
affected sources, units before/after, the mechanical default, the selected
normalization, rationale and context. The normalizer never edits the generated
inventory.

## 5. Coverage invariants

Let `C` = candidate source records, `E` = evidence source records, `U` = review
units.

**A. Candidate coverage.** Every candidate belongs to at least one unit, or is
explicitly scope-excluded with a documented reason. Round 0: 270/270 linked, 0
omitted.

**B. Evidence accountability.** Every evidence record is referenced by a unit
or has a non-empty orphan reason. Round 0: 241/241 linked, 0 orphaned.

**C. Review-unit accountability.** Every unit has `REVIEWED`/`PENDING` status.
`REVIEWED` needs a valid disposition and rationale. `PENDING` needs `reason`
and `blocker` and no final disposition. Every GAP references a real unit and
has all required fields.

**D. Referential integrity.** All source/unit references in units, scope
exclusions and normalization decisions exist; IDs are unique; roles are not
conflicting; candidate dispositions do not conflict. The model's counts are
recomputed, not trusted; stale generated/reviewed hashes fail validation.

The validator also enforces extension-surface accountability: every
`extension_surface` is unit-referenced or scope-excluded with a reason and an
existing decision.

## 6. Dispatch boundary

The audit rule is:

> The content dispatch point itself is recorded, but its content implementation
> is not expanded. Framework-owned code before and after the dispatch remains
> part of the form being audited.

The extractor records all direct calls in the handler body, branch/switch
counts, and `invokedynamic` counts, not just the first virtual call. Reviewed
dispatch anchors point at framework methods. Content overrides of blocks,
items, entities and menus are not recursively expanded.

Limitations:

- direct invocations are exact per method, but there is no transitive call
  graph;
- `invokedynamic`/lambda targets are unresolved (for example the
  `BlockGetter.forEachBlockIntersectedBetween` visitor used by
  `Entity.checkInsideBlocks`);
- framework return-value interpretation is visible only where it is a direct
  call or branch in the handler, not as a data-flow proof.

## 7. Future extractor improvements (recorded, not implemented)

### 7.1 Lambda/`invokedynamic` target resolution

**Proposal.** For each `InvokeDynamicInstruction`, resolve the bootstrap method
handle (`LambdaMetafactory.metafactory`/`altMetafactory`), read the
implementation `MethodHandle` bootstrap argument, and record its owner, name and
descriptor as a *lambda target* edge. Normalize synthetic lambda names.

**Inputs/API.** JDK 25 `ClassFile` API: `InvokeDynamicInstruction.bootstrapMethod()`,
`bootstrapArgs()`, `DirectMethodHandleDesc`. No new library.

**Reliability.** High for standard javac lambdas and method references; lower
for custom bootstraps, captured generic bridges and obfuscated/synthetic names.

**Limitations.** Does not compute reachability; only resolves the target edge.
Nested lambdas require recursion; duplicate targets must be deduplicated by
symbol, not lambda name.

**Verification.** Add a fixture class with a lambda and a method reference;
assert both target descriptors; compare against `javap -c` for the same method;
run against `Entity.checkInsideBlocks` and confirm the visitor target appears.

**Most useful for.** Passive-embodiment review (`entityInside` dispatch,
collision visitors) and handlers whose framework loop is lambda-based.

### 7.2 Normalized method fingerprint

**Proposal.** Replace raw `Code`-attribute hashing with a canonical instruction
digest: opcode plus resolved symbolic operands (class/method/field names and
descriptors, constants), with branch targets normalized to instruction indices
and line/local/stack-map attributes omitted. Hash the method descriptor plus the
canonical stream.

**Inputs/API.** JDK 25 `CodeModel`/instruction subtypes and constant-pool
resolution; SHA-256. No new library.

**Reliability.** High for javac output; stable across constant-pool reordering
and debug metadata changes.

**Limitations.** Recompilation can still change inlining/bridge structure;
generic signatures and synthetic accessors need explicit normalization rules;
the digest still does not prove semantic equivalence.

**Verification.** Compile one fixture twice with/without an unrelated constant
pool entry and assert equal digests; change one opcode and assert a different
digest; compare a small set of real handlers across two extractor runs.

**Most useful for.** All reviewed handler/passive units; especially high-churn
handlers where a raw hash currently triggers review for non-semantic encoding
changes. The existing raw hash remains a conservative trigger until this is
implemented.

## 8. Blind-review consistency check (optional)

A second reviewer can receive a package containing:

```json
{
  "packageId": "BR-001",
  "unitId": "RU.<id>",
  "sources": ["..."],
  "evidence": ["..."],
  "constraints": ["..."],
  "question": "Choose a disposition and explain it from the evidence."
}
```

The original `disposition`, `proposal`, `rationale` and `reason` are withheld.
The reviewer records an independent disposition, rationale and confidence. A
comparison records agreement/disagreement and treats disagreements as review
targets, not automatic errors. A sample package can be produced by deleting the
`disposition`/`rationale` fields from a normalized unit; no campaign is required
for Round 0.

## 9. Artifacts and commands

| Artifact | Content |
|---|---|
| `completeness.generated.json` | mechanical facts; never hand-edited |
| `completeness.normalized.json` | roles, sources, default/final units, scope exclusions, counts |
| `completeness.normalization.json` | normalization decisions ledger |
| `completeness.reviewed.md` | human review decisions (source of dispositions) |

```powershell
.\tools\audit\regenerate.ps1
python tools\audit\normalize_audit.py
python tools\audit\validate_completeness.py `
  --generated docs\auditing\completeness.generated.json `
  --reviewed docs\auditing\completeness.reviewed.md
python -m unittest discover -s tools\audit\tests -v
```

The validator is advisory; `--strict` treats review findings as failures. It is
not a CI completeness gate.
