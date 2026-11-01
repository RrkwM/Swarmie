#!/usr/bin/env python3
"""Validate the primitive audit: generated facts, normalized units, reviewed catalogue.

Two layers are checked:

* catalogue checks: fingerprints, dispatch anchors, pending-entry fields;
* normalized-model checks (when completeness.normalized.json exists): roles,
  candidate coverage, evidence accountability, review-unit accountability,
  referential integrity and GAP validity.

The validator is advisory by default. Structural errors exit 1; review findings
(including unreviewed units) exit 0 unless --strict is passed. It is not a
mandatory CI completeness gate.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Set, Tuple

DISPOSITIONS = {"MAPPED", "PRIVILEGED", "EXCLUDED", "CONTENT", "PROTOCOL_MECHANISM"}
TEMPORAL = {"QUERY", "SETPOINT", "ACTION", "n/a"}
STATUSES = {"accepted", "proposed", "pending", "blocked", "retired"}
REVIEW_STATUSES = {"REVIEWED", "PENDING"}
ROLES = {"candidate", "evidence", "extension_surface"}
OPERATIONS = {"SPLIT", "MERGE", "GROUP", "RECLASSIFY", "SCOPE", "RULE"}
REQUIRED_ACCEPTED = ("sources", "disposition", "domain", "operation", "rationale", "fingerprint")


@dataclass
class ReviewedEntry:
    entry_id: str
    line: int
    fields: Dict[str, str]

    @property
    def sources(self) -> List[str]:
        raw = self.fields.get("sources", "")
        return [part.strip() for part in raw.split(",") if part.strip()]

    @property
    def status(self) -> str:
        return self.fields.get("status", "proposed")


@dataclass(frozen=True)
class Finding:
    level: str  # "error" | "review"
    code: str
    message: str
    entry: Optional[str] = None
    source: Optional[str] = None
    unit: Optional[str] = None

    def __str__(self) -> str:
        where = f" [{self.entry}]" if self.entry else ""
        if not where and self.unit:
            where = f" [{self.unit}]"
        target = f" <{self.source}>" if self.source else ""
        return f"{self.level.upper()} {self.code}{where}{target}: {self.message}"


def load_generated(path: str | Path) -> Dict[str, Any]:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(data, dict) or not isinstance(data.get("sources"), list):
        raise ValueError("generated inventory must contain a sources array")
    return data


def parse_reviewed(path: str | Path) -> List[ReviewedEntry]:
    entries: List[ReviewedEntry] = []
    current: Optional[ReviewedEntry] = None
    for number, raw in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        line = raw.rstrip()
        if line.startswith("## "):
            if current is not None:
                entries.append(current)
            current = ReviewedEntry(line[3:].strip(), number, {})
            continue
        if current is not None and line.startswith("- ") and ": " in line:
            key, value = line[2:].split(": ", 1)
            current.fields[key.strip()] = value.strip()
    if current is not None:
        entries.append(current)
    return entries


def _fingerprint_map(entry: ReviewedEntry, errors: List[Finding]) -> List[Tuple[str, str]]:
    raw = entry.fields.get("fingerprint", "")
    if not raw:
        return []
    result: List[Tuple[str, str]] = []
    for part in raw.split("; "):
        if "=" not in part:
            errors.append(Finding("error", "INVALID_FINGERPRINT",
                                  f"fingerprint must be SOURCE_ID=HASH, got {part!r}",
                                  entry.entry_id))
            continue
        source_id, digest = part.split("=", 1)
        result.append((source_id.strip(), digest.strip()))
    return result


def _dispatch(entry: ReviewedEntry, errors: List[Finding]) -> Optional[Tuple[str, str]]:
    raw = entry.fields.get("dispatch")
    if not raw:
        return None
    if "=" not in raw:
        errors.append(Finding("error", "INVALID_DISPATCH",
                              f"dispatch must be SOURCE_ID=SUBSTRING, got {raw!r}",
                              entry.entry_id))
        return None
    source_id, needle = raw.split("=", 1)
    return source_id.strip(), needle.strip()


def _calls(source: Dict[str, Any]) -> List[str]:
    handler = source.get("handler")
    if isinstance(handler, dict) and isinstance(handler.get("calls"), list):
        return [str(item) for item in handler["calls"]]
    calls = source.get("calls")
    return [str(item) for item in calls] if isinstance(calls, list) else []


def validate(
    generated: Dict[str, Any],
    reviewed: List[ReviewedEntry],
    previous: Optional[Dict[str, Any]] = None,
    list_unreviewed: int = 20,
    include_unreviewed: bool = True,
) -> List[Finding]:
    findings: List[Finding] = []
    sources: Dict[str, Dict[str, Any]] = {}
    for source in generated["sources"]:
        source_id = source.get("sourceId")
        if not source_id:
            findings.append(Finding("error", "MISSING_SOURCE_ID", "source entry has no sourceId"))
            continue
        if source_id in sources:
            findings.append(Finding("error", "DUPLICATE_SOURCE_ID",
                                    "duplicate sourceId in generated inventory", source=source_id))
        sources[source_id] = source

    reviewed_sources: Set[str] = set()
    removal_confirmed: Set[str] = set()

    for entry in reviewed:
        for required in ("status",):
            if required not in entry.fields:
                findings.append(Finding("error", "INVALID_ENTRY",
                                        f"missing field {required}", entry.entry_id))
        if entry.status not in STATUSES:
            findings.append(Finding("error", "INVALID_STATUS",
                                    f"unknown status {entry.status!r}", entry.entry_id))
        disposition = entry.fields.get("disposition")
        if disposition is not None and disposition not in DISPOSITIONS:
            findings.append(Finding("error", "INVALID_DISPOSITION",
                                    f"unknown disposition {disposition!r}", entry.entry_id))
        temporal = entry.fields.get("temporal")
        if temporal is not None and temporal not in TEMPORAL:
            findings.append(Finding("error", "INVALID_TEMPORAL",
                                    f"unknown temporal semantics {temporal!r}", entry.entry_id))

        if entry.status == "accepted":
            for required in REQUIRED_ACCEPTED:
                if not entry.fields.get(required):
                    findings.append(Finding("error", "INVALID_ENTRY",
                                            f"accepted entry needs {required}", entry.entry_id))
        elif entry.status in ("pending", "proposed", "blocked"):
            if disposition is not None:
                findings.append(Finding("error", "PENDING_DISPOSITION",
                                        "a pending entry must not carry a final disposition; use proposal",
                                        entry.entry_id))
            if not entry.fields.get("reason"):
                findings.append(Finding("error", "PENDING_WITHOUT_REASON",
                                        "pending entry needs reason", entry.entry_id))
            if not entry.fields.get("blocker"):
                findings.append(Finding("error", "PENDING_WITHOUT_BLOCKER",
                                        "pending entry needs blocker", entry.entry_id))
            proposal = entry.fields.get("proposal")
            if proposal is not None and proposal not in DISPOSITIONS:
                findings.append(Finding("error", "INVALID_PROPOSAL",
                                        f"unknown provisional disposition {proposal!r}",
                                        entry.entry_id))

        entry_sources = entry.sources
        if not entry_sources:
            findings.append(Finding("error", "NO_SOURCES",
                                    "entry references no sourceId", entry.entry_id))
        for source_id in entry_sources:
            reviewed_sources.add(source_id)
            if source_id not in sources:
                if entry.fields.get("removal") == "confirmed":
                    removal_confirmed.add(source_id)
                    findings.append(Finding("review", "REMOVAL_CONFIRMED",
                                            "reviewed source is gone; removal was confirmed",
                                            entry.entry_id, source_id))
                else:
                    findings.append(Finding("error", "UNKNOWN_SOURCE",
                                            "reviewed entry references a nonexistent source",
                                            entry.entry_id, source_id))

        for source_id, digest in _fingerprint_map(entry, findings):
            reviewed_sources.add(source_id)
            if source_id not in sources:
                findings.append(Finding("error", "UNKNOWN_FINGERPRINT_SOURCE",
                                        "fingerprint references a nonexistent source",
                                        entry.entry_id, source_id))
                continue
            actual = str(sources[source_id].get("fingerprint", ""))
            if actual != digest:
                findings.append(Finding(
                    "review", "FINGERPRINT_CHANGED",
                    "method/class fingerprint changed; review the implementation, "
                    "do not assume the semantic meaning changed",
                    entry.entry_id, source_id))

        dispatch = _dispatch(entry, findings)
        if dispatch is not None:
            source_id, needle = dispatch
            if source_id not in sources:
                findings.append(Finding("error", "UNKNOWN_DISPATCH_SOURCE",
                                        "dispatch references a nonexistent source",
                                        entry.entry_id, source_id))
            elif needle and not any(needle in call for call in _calls(sources[source_id])):
                findings.append(Finding(
                    "review", "DISPATCH_CHANGED",
                    f"dispatch anchor {needle!r} no longer appears in the extracted calls",
                    entry.entry_id, source_id))

    if include_unreviewed:
        covered = set(reviewed_sources)
        changed = True
        while changed:
            changed = False
            for source in generated["sources"]:
                source_id = source.get("sourceId")
                if source_id not in covered and source.get("parentId") in covered:
                    covered.add(source_id)
                    changed = True
        unreviewed = sorted(source_id for source_id in sources if source_id not in covered)
        if unreviewed:
            sample = ", ".join(unreviewed[:list_unreviewed])
            suffix = "" if len(unreviewed) <= list_unreviewed else f" (+{len(unreviewed) - list_unreviewed} more)"
            findings.append(Finding(
                "review", "UNREVIEWED_SOURCES",
                f"{len(unreviewed)} source(s) have no reviewed disposition: {sample}{suffix}"))

    if previous is not None:
        old = {source["sourceId"] for source in previous.get("sources", []) if source.get("sourceId")}
        new = set(sources)
        for source_id in sorted(new - old):
            findings.append(Finding("review", "NEW_SOURCE",
                                    "new source requires a disposition", source=source_id))
        for source_id in sorted(old - new):
            if source_id in removal_confirmed:
                continue
            findings.append(Finding("error", "DISAPPEARED_SOURCE",
                                    "source disappeared; confirm removal, deprecation or migration",
                                    source=source_id))
        old_fingerprints = {
            source["sourceId"]: source.get("fingerprint")
            for source in previous.get("sources", [])
            if source.get("sourceId")
        }
        for source_id in sorted(new & old):
            before = old_fingerprints.get(source_id)
            after = sources[source_id].get("fingerprint")
            if before is not None and after is not None and before != after:
                findings.append(Finding("review", "FINGERPRINT_CHANGED",
                                        "fingerprint changed since the previous generated inventory",
                                        source=source_id))

    return findings


def validate_model(
    generated: Dict[str, Any],
    model: Dict[str, Any],
    decisions: Dict[str, Any],
    generated_sha256: Optional[str] = None,
    reviewed_sha256: Optional[str] = None,
) -> List[Finding]:
    findings: List[Finding] = []
    generated_ids = {source.get("sourceId") for source in generated.get("sources", [])}
    if not generated_ids:
        return [Finding("error", "EMPTY_GENERATED", "generated inventory has no sources")]

    if model.get("format") != "swarmie-audit-model/1":
        findings.append(Finding("error", "BAD_MODEL_FORMAT",
                                f"unexpected model format {model.get('format')!r}"))
    if generated_sha256 is not None and model.get("generatedSha256") != generated_sha256:
        findings.append(Finding("error", "STALE_MODEL",
                                "normalized model does not match the generated inventory hash"))
    if reviewed_sha256 is not None and model.get("reviewedSha256") != reviewed_sha256:
        findings.append(Finding("error", "STALE_MODEL",
                                "normalized model does not match the reviewed catalogue hash"))

    sources: Dict[str, Dict[str, Any]] = {}
    for source in model.get("sources", []):
        source_id = source.get("sourceId")
        if not source_id:
            findings.append(Finding("error", "MODEL_SOURCE_ID_MISSING", "model source has no sourceId"))
            continue
        if source_id in sources:
            findings.append(Finding("error", "MODEL_SOURCE_DUPLICATE",
                                    "duplicate model sourceId", source=source_id))
        sources[source_id] = source
        role = source.get("role")
        if role not in ROLES:
            findings.append(Finding("error", "INVALID_ROLE", f"invalid role {role!r}", source=source_id))
        generated_source = source.get("generatedSourceId")
        if generated_source and generated_source not in generated_ids:
            findings.append(Finding("error", "BROKEN_GENERATED_LINK",
                                    "model source points at a missing generated source",
                                    source=source_id))

    for source_id in generated_ids:
        if source_id not in sources:
            findings.append(Finding("error", "GENERATED_SOURCE_LOST",
                                    "generated source is absent from the normalized model",
                                    source=source_id))

    role_ids = {
        role: {sid for sid, source in sources.items() if source.get("role") == role}
        for role in ROLES
    }

    units: Dict[str, Dict[str, Any]] = {}
    for unit in model.get("reviewUnits", []):
        unit_id = unit.get("unitId")
        if not unit_id:
            findings.append(Finding("error", "UNIT_ID_MISSING", "review unit has no unitId"))
            continue
        if unit_id in units:
            findings.append(Finding("error", "UNIT_DUPLICATE", "duplicate review-unit id", unit=unit_id))
        units[unit_id] = unit

    default_units: Dict[str, Dict[str, Any]] = {}
    for unit in model.get("defaultUnits", []):
        unit_id = unit.get("unitId")
        if unit_id:
            default_units[unit_id] = unit
    all_unit_ids = set(units) | set(default_units)

    scope = model.get("scopeExclusions", [])
    scope_ids = {item.get("sourceId") for item in scope if item.get("sourceId")}
    orphan_reasons = model.get("orphanReasons", {})
    if not isinstance(orphan_reasons, dict):
        findings.append(Finding("error", "BAD_ORPHAN_REASONS", "orphanReasons must be an object"))
        orphan_reasons = {}

    referenced_candidates: Set[str] = set()
    referenced_evidence: Set[str] = set()
    referenced_extensions: Set[str] = set()
    candidate_dispositions: Dict[str, Set[str]] = {}

    for unit_id, unit in units.items():
        family = unit.get("family")
        if family not in {"serverbound_play", "serverbound_common", "clientbound_play",
                          "passive_embodied", "fabric_extension"}:
            findings.append(Finding("error", "INVALID_FAMILY", f"invalid family {family!r}", unit=unit_id))
        status = unit.get("status")
        if status not in REVIEW_STATUSES:
            findings.append(Finding("error", "INVALID_REVIEW_STATUS",
                                    f"invalid status {status!r}", unit=unit_id))
            continue
        candidates = unit.get("candidateSourceIds") or []
        evidence = unit.get("evidenceSourceIds") or []
        extensions = unit.get("extensionSourceIds") or []
        if not candidates and not extensions:
            findings.append(Finding("error", "EMPTY_UNIT",
                                    "unit references no candidate or extension sources", unit=unit_id))
        for sid in candidates:
            if sid not in sources:
                findings.append(Finding("error", "UNIT_UNKNOWN_SOURCE",
                                        "unit references a missing source", unit=unit_id, source=sid))
            elif sources[sid]["role"] != "candidate":
                findings.append(Finding("error", "UNIT_ROLE_MISMATCH",
                                        "candidateSourceIds references a non-candidate",
                                        unit=unit_id, source=sid))
            else:
                referenced_candidates.add(sid)
                if status == "REVIEWED" and unit.get("disposition"):
                    candidate_dispositions.setdefault(sid, set()).add(unit["disposition"])
        for sid in evidence:
            if sid not in sources:
                findings.append(Finding("error", "UNIT_UNKNOWN_EVIDENCE",
                                        "unit references missing evidence", unit=unit_id, source=sid))
            elif sources[sid]["role"] != "evidence":
                findings.append(Finding("error", "UNIT_EVIDENCE_ROLE",
                                        "evidenceSourceIds references a non-evidence",
                                        unit=unit_id, source=sid))
            else:
                referenced_evidence.add(sid)
        for sid in extensions:
            if sid not in sources:
                findings.append(Finding("error", "UNIT_UNKNOWN_EXTENSION",
                                        "unit references missing extension surface",
                                        unit=unit_id, source=sid))
            elif sources[sid]["role"] != "extension_surface":
                findings.append(Finding("error", "UNIT_EXTENSION_ROLE",
                                        "extensionSourceIds references a non-extension",
                                        unit=unit_id, source=sid))
            else:
                referenced_extensions.add(sid)

        if status == "REVIEWED":
            if unit.get("disposition") not in DISPOSITIONS:
                findings.append(Finding("error", "REVIEWED_WITHOUT_DISPOSITION",
                                        "REVIEWED unit needs a valid disposition", unit=unit_id))
            if not unit.get("rationale"):
                findings.append(Finding("error", "REVIEWED_WITHOUT_RATIONALE",
                                        "REVIEWED unit needs a rationale", unit=unit_id))
        else:
            if unit.get("disposition") is not None:
                findings.append(Finding("error", "PENDING_WITH_DISPOSITION",
                                        "PENDING unit must not carry a final disposition", unit=unit_id))
            if not unit.get("reason"):
                findings.append(Finding("error", "PENDING_WITHOUT_REASON",
                                        "PENDING unit needs reason", unit=unit_id))
            if not unit.get("blocker"):
                findings.append(Finding("error", "PENDING_WITHOUT_BLOCKER",
                                        "PENDING unit needs blocker", unit=unit_id))
            proposal = unit.get("proposal")
            if proposal is not None and proposal not in DISPOSITIONS:
                findings.append(Finding("error", "INVALID_PROPOSAL",
                                        f"invalid provisional disposition {proposal!r}", unit=unit_id))

    # Invariant A: candidate coverage.
    omitted_candidates = sorted(role_ids["candidate"] - referenced_candidates - scope_ids)
    if omitted_candidates:
        findings.append(Finding("error", "CANDIDATE_OMITTED",
                                f"{len(omitted_candidates)} candidate(s) are in no review unit "
                                f"and not scope-excluded: {', '.join(omitted_candidates[:10])}"))

    # Invariant B: evidence accountability.
    orphaned = sorted(
        sid for sid in role_ids["evidence"]
        if sid not in referenced_evidence
        and not str(orphan_reasons.get(sid, "")).strip()
    )
    if orphaned:
        findings.append(Finding("error", "EVIDENCE_ORPHANED",
                                f"{len(orphaned)} evidence record(s) are unreferenced and lack an orphan reason: "
                                f"{', '.join(orphaned[:10])}"))

    # Extension surfaces are inventoried; unreferenced ones need a scope decision.
    for sid in sorted(role_ids["extension_surface"] - referenced_extensions):
        item = next((entry for entry in scope if entry.get("sourceId") == sid), None)
        if item is None or not str(item.get("reason", "")).strip():
            findings.append(Finding("error", "EXTENSION_SURFACE_OMITTED",
                                    "extension surface is neither unit-referenced nor scope-excluded",
                                    source=sid))

    # Candidate identity must be unique across units.
    unit_candidates: Dict[str, List[str]] = {}
    for unit_id, unit in units.items():
        for sid in unit.get("candidateSourceIds") or []:
            unit_candidates.setdefault(sid, []).append(unit_id)
    for sid, unit_ids in sorted(unit_candidates.items()):
        if len(unit_ids) > 1:
            findings.append(Finding("error", "CANDIDATE_MULTI_UNIT",
                                    f"candidate is in multiple units: {', '.join(unit_ids)}",
                                    source=sid))
    for sid, dispositions in sorted(candidate_dispositions.items()):
        if len(dispositions) > 1:
            findings.append(Finding("error", "CONFLICTING_DISPOSITIONS",
                                    f"candidate has conflicting REVIEWED dispositions: {sorted(dispositions)}",
                                    source=sid))

    # GAP findings.
    for gap in model.get("gaps", []):
        unit_id = gap.get("unitId")
        if unit_id not in units:
            findings.append(Finding("error", "GAP_UNKNOWN_UNIT",
                                    "GAP references a nonexistent review unit", unit=unit_id))
        for field in ("behaviour", "whyPublic", "whyNotExisting", "evidence"):
            if not str(gap.get(field, "")).strip():
                findings.append(Finding("error", "GAP_INCOMPLETE",
                                        f"GAP unit is missing {field}", unit=unit_id))

    # Invariant D: decisions and references.
    decision_ids: Set[str] = set()
    for decision in decisions.get("decisions", []):
        decision_id = decision.get("decisionId")
        if not decision_id:
            findings.append(Finding("error", "DECISION_ID_MISSING", "decision has no decisionId"))
            continue
        if decision_id in decision_ids:
            findings.append(Finding("error", "DECISION_DUPLICATE",
                                    "duplicate decision id", entry=decision_id))
        decision_ids.add(decision_id)
        if decision.get("operation") not in OPERATIONS:
            findings.append(Finding("error", "INVALID_DECISION_OPERATION",
                                    f"invalid operation {decision.get('operation')!r}",
                                    entry=decision_id))
        for sid in decision.get("affectedSourceIds", []):
            if sid not in sources:
                findings.append(Finding("error", "DECISION_UNKNOWN_SOURCE",
                                        "decision references a missing source",
                                        entry=decision_id, source=sid))
        for unit_id in decision.get("unitsBefore", []) + decision.get("unitsAfter", []):
            if unit_id not in all_unit_ids:
                findings.append(Finding("error", "DECISION_UNKNOWN_UNIT",
                                        "decision references a missing review unit",
                                        entry=decision_id, source=unit_id))
        if not str(decision.get("rationale", "")).strip():
            findings.append(Finding("error", "DECISION_WITHOUT_RATIONALE",
                                    "normalization decision needs a rationale", entry=decision_id))

    for item in scope:
        sid = item.get("sourceId")
        if sid not in sources:
            findings.append(Finding("error", "SCOPE_UNKNOWN_SOURCE",
                                    "scope exclusion references a missing source", source=sid))
        if not str(item.get("reason", "")).strip():
            findings.append(Finding("error", "SCOPE_WITHOUT_REASON",
                                    "scope exclusion needs a reason", source=sid))
        if item.get("decisionId") not in decision_ids:
            findings.append(Finding("error", "SCOPE_UNKNOWN_DECISION",
                                    "scope exclusion references a missing decision", source=sid))

    # Counts must be recomputed, not trusted.
    counts = model.get("counts")
    if isinstance(counts, dict):
        expected = {
            "reviewUnits": len(units),
            "candidates": len(role_ids["candidate"]),
            "evidence": len(role_ids["evidence"]),
            "extensionSurfaces": len(role_ids["extension_surface"]),
            "candidatesLinked": len(referenced_candidates),
            "evidenceLinked": len(referenced_evidence),
            "evidenceOrphans": 0,
            "gaps": len(model.get("gaps", [])),
        }
        for key, value in expected.items():
            if counts.get(key) != value:
                findings.append(Finding("error", "STALE_COUNTS",
                                        f"counts.{key} is {counts.get(key)!r}, expected {value!r}"))

    return findings


def nondeterminism(a: Dict[str, Any], b: Dict[str, Any]) -> List[Finding]:
    """A repeat run over unchanged inputs must be byte-for-byte equivalent."""
    if json.dumps(a, sort_keys=True) != json.dumps(b, sort_keys=True):
        return [Finding("error", "NONDETERMINISM",
                        "two runs over identical inputs produced different inventories")]
    return []


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _print_model_summary(model: Dict[str, Any]) -> None:
    counts = model.get("counts", {})
    print(
        "model: sources={sources} candidates={candidates} evidence={evidence} "
        "extension_surfaces={ext} units={units} reviewed={reviewed} pending={pending}".format(
            sources=counts.get("sourceRecords"),
            candidates=counts.get("roles", {}).get("candidate"),
            evidence=counts.get("roles", {}).get("evidence"),
            ext=counts.get("roles", {}).get("extension_surface"),
            units=counts.get("reviewUnits"),
            reviewed=counts.get("reviewStatus", {}).get("REVIEWED"),
            pending=counts.get("reviewStatus", {}).get("PENDING"),
        )
    )
    print("units by family:", json.dumps(counts.get("reviewUnitsByFamily", {}), sort_keys=True))
    print("status by family:", json.dumps(counts.get("reviewUnitsByFamilyStatus", {}), sort_keys=True))
    print("clientbound units:", counts.get("clientboundReviewUnits"),
          "clientbound pending:", counts.get("clientboundPending"))


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Validate primitive audit artifacts")
    parser.add_argument("--generated", required=True)
    parser.add_argument("--reviewed", required=True)
    parser.add_argument("--model", help="normalized model (default: sibling completeness.normalized.json)")
    parser.add_argument("--decisions", help="normalization ledger (default: sibling completeness.normalization.json)")
    parser.add_argument("--previous", help="previous generated inventory for new/disappeared detection")
    parser.add_argument("--repeat", help="second generated inventory from the same inputs")
    parser.add_argument("--strict", action="store_true", help="treat review findings as failures")
    parser.add_argument("--list-unreviewed", type=int, default=20)
    parser.add_argument("--json-report")
    args = parser.parse_args(argv)

    generated_path = Path(args.generated)
    reviewed_path = Path(args.reviewed)
    generated = load_generated(generated_path)
    reviewed = parse_reviewed(reviewed_path)
    previous = load_generated(args.previous) if args.previous else None
    model_path = Path(args.model) if args.model else generated_path.with_name("completeness.normalized.json")
    decisions_path = (
        Path(args.decisions) if args.decisions else generated_path.with_name("completeness.normalization.json")
    )

    has_model = model_path.exists() and decisions_path.exists()
    findings = validate(
        generated, reviewed, previous, args.list_unreviewed, include_unreviewed=not has_model
    )

    model = None
    if has_model:
        model = json.loads(model_path.read_text(encoding="utf-8"))
        decisions = json.loads(decisions_path.read_text(encoding="utf-8"))
        findings.extend(
            validate_model(
                generated,
                model,
                decisions,
                generated_sha256=_sha256(generated_path),
                reviewed_sha256=_sha256(reviewed_path),
            )
        )
        _print_model_summary(model)
    else:
        print(f"catalogue-only: reviewed_entries={len(reviewed)}")

    if args.repeat:
        repeat = load_generated(args.repeat)
        findings.extend(nondeterminism(generated, repeat))

    for finding in findings:
        print(finding)

    errors = [finding for finding in findings if finding.level == "error"]
    review = [finding for finding in findings if finding.level == "review"]
    print(f"errors={len(errors)} review_findings={len(review)}")
    if errors or (args.strict and review):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
