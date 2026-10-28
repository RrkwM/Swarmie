#!/usr/bin/env python3
"""Normalize the generated primitive inventory into review units.

The normalizer is deterministic. It assigns record roles from record types,
builds default review units from explicit discriminator splits, maps the
reviewed catalogue onto them, and emits a traceable normalization ledger.
It does not decide semantic dispositions for unreviewed units.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Set, Tuple

from validate_completeness import ReviewedEntry, parse_reviewed

MODEL_FORMAT = "swarmie-audit-model/1"
DECISION_FORMAT = "swarmie-normalization-decisions/1"

FAMILY_LABEL = {
    "serverbound_play": "serverbound_play",
    "serverbound_common": "serverbound_common",
    "clientbound_play": "clientbound_play",
    "passive_embodied": "passive_embodied",
    "fabric_extension": "fabric_extension",
}

DEFAULT_REASON = {
    "serverbound_play": "No reviewed disposition for this active-ingress candidate.",
    "serverbound_common": "No reviewed disposition for this common-protocol candidate.",
    "clientbound_play": "Clientbound observation decision deferred; no final disposition.",
    "passive_embodied": "No reviewed disposition for this passive-embodied hook.",
    "fabric_extension": "No reviewed disposition for this extension surface.",
}
DEFAULT_BLOCKER = {
    "serverbound_play": "Round 1/2 active-ingress semantic review not started.",
    "serverbound_common": "Round 1 common-protocol review not started.",
    "clientbound_play": "Round 3 observation/self-state review not started.",
    "passive_embodied": "Round 2 passive-embodiment review not started.",
    "fabric_extension": "Round 0 extension-surface scope decision pending.",
}


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json_lf(path: Path, value: Any) -> None:
    """Write UTF-8 with LF explicitly so Windows and Linux outputs match."""
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(value, indent=2, sort_keys=True) + "\n")


def _family(source: Dict[str, Any]) -> str:
    category = source["category"]
    if category == "serverbound_play":
        return "serverbound_play"
    if category == "serverbound_common":
        return "serverbound_common"
    if category == "clientbound_play":
        return "clientbound_play"
    if category == "passive_embodied":
        return "passive_embodied"
    if category == "fabric_extension":
        return "fabric_extension"
    raise ValueError(f"unknown source category: {category}")


def _source_record(source: Dict[str, Any], role: str, record_type: str) -> Dict[str, Any]:
    return {
        "sourceId": source["sourceId"],
        "role": role,
        "family": _family(source),
        "recordType": record_type,
        "generatedSourceId": source["sourceId"],
    }


def _evidence_record(
    source_id: str, family: str, record_type: str, generated_source_id: str, summary: Dict[str, Any]
) -> Dict[str, Any]:
    return {
        "sourceId": source_id,
        "role": "evidence",
        "family": family,
        "recordType": record_type,
        "generatedSourceId": generated_source_id,
        "summary": summary,
    }


def _unit(
    unit_id: str,
    family: str,
    candidates: Iterable[str],
    evidence: Iterable[str],
    status: str,
    *,
    title: str = "",
    disposition: Optional[str] = None,
    rationale: str = "",
    proposal: Optional[str] = None,
    reason: Optional[str] = None,
    blocker: Optional[str] = None,
    reviewed_entry: Optional[str] = None,
    extension_sources: Iterable[str] = (),
) -> Dict[str, Any]:
    return {
        "unitId": unit_id,
        "family": family,
        "title": title,
        "candidateSourceIds": sorted(set(candidates)),
        "evidenceSourceIds": sorted(set(evidence)),
        "extensionSourceIds": sorted(set(extension_sources)),
        "status": status,
        "disposition": disposition,
        "rationale": rationale,
        "proposal": proposal,
        "reason": reason,
        "blocker": blocker,
        "reviewedEntry": reviewed_entry,
        "gap": None,
    }


def normalize(generated: Dict[str, Any], reviewed: List[ReviewedEntry]) -> Tuple[Dict[str, Any], Dict[str, Any]]:
    sources = generated["sources"]
    by_id = {source["sourceId"]: source for source in sources}
    if len(by_id) != len(sources):
        raise ValueError("duplicate sourceId in generated inventory")

    children: Dict[str, List[str]] = defaultdict(list)
    for source in sources:
        parent = source.get("parentId")
        if parent:
            children[parent].append(source["sourceId"])
    for values in children.values():
        values.sort()

    records: Dict[str, Dict[str, Any]] = {}
    evidence: Dict[str, Dict[str, Any]] = {}
    extension_ids: List[str] = []

    # Deterministic role rules. Packet envelopes are evidence because their
    # discriminator values are the candidate units; handler/call/fingerprint
    # facts become separate evidence records.
    for source in sources:
        sid = source["sourceId"]
        family = _family(source)
        if family in ("serverbound_play", "serverbound_common"):
            if source["kind"] == "packet_variant":
                records[sid] = _source_record(source, "candidate", "action_variant")
            elif source["kind"] == "packet":
                if children.get(sid):
                    records[sid] = _source_record(source, "evidence", "packet_envelope")
                else:
                    records[sid] = _source_record(source, "candidate", "packet")
            else:
                raise ValueError(f"unexpected serverbound record kind: {source['kind']}")
            if source["kind"] == "packet":
                handler = source.get("handler", {})
                evidence["EV.HANDLER." + sid] = _evidence_record(
                    "EV.HANDLER." + sid,
                    family,
                    "handler_method",
                    sid,
                    {
                        "method": handler.get("method"),
                        "implementation": handler.get("implementation"),
                        "fingerprint": source.get("fingerprint"),
                        "fingerprintKind": source.get("fingerprintKind"),
                        "calls": len(handler.get("calls", [])),
                    },
                )
        elif family == "clientbound_play":
            records[sid] = _source_record(source, "candidate", "clientbound_packet")
            evidence["EV.CLASS." + sid] = _evidence_record(
                "EV.CLASS." + sid,
                family,
                "class_fingerprint",
                sid,
                {"fingerprint": source.get("fingerprint"), "fields": len(source.get("fields", []))},
            )
        elif family == "passive_embodied":
            if not source.get("found", True):
                records[sid] = _source_record(source, "evidence", "missing_seed")
                continue
            records[sid] = _source_record(source, "candidate", "passive_method")
            evidence["EV.METHOD." + sid] = _evidence_record(
                "EV.METHOD." + sid,
                family,
                "method_fact",
                sid,
                {
                    "method": source.get("method"),
                    "descriptor": source.get("descriptor"),
                    "fingerprint": source.get("fingerprint"),
                    "invokedynamicCount": source.get("invokedynamicCount", 0),
                },
            )
        elif family == "fabric_extension":
            records[sid] = _source_record(source, "extension_surface", "extension_signature")
            extension_ids.append(sid)
        else:
            raise ValueError(f"unhandled family {family}")

    # Default units: one per candidate, except extension surfaces, which are
    # accounted for but are not semantic candidates.
    default_units: Dict[str, Dict[str, Any]] = {}
    source_to_units: Dict[str, List[str]] = defaultdict(list)
    for sid, record in records.items():
        if record["role"] != "candidate":
            continue
        family = record["family"]
        unit_id = "U." + sid
        unit_evidence: List[str] = []
        if record["recordType"] == "action_variant":
            parent = by_id[sid].get("parentId")
            if parent:
                unit_evidence.append(parent)
                unit_evidence.append("EV.HANDLER." + parent)
        elif record["recordType"] == "packet":
            unit_evidence.append("EV.HANDLER." + sid)
        elif record["recordType"] == "clientbound_packet":
            unit_evidence.append("EV.CLASS." + sid)
        elif record["recordType"] == "passive_method":
            unit_evidence.append("EV.METHOD." + sid)
        default_units[unit_id] = _unit(
            unit_id,
            family,
            [sid],
            unit_evidence,
            "PENDING",
            reason=DEFAULT_REASON[family],
            blocker=DEFAULT_BLOCKER[family],
        )
        source_to_units[sid].append(unit_id)

    # A packet envelope maps to all of its discriminator child units.
    for parent, child_ids in children.items():
        child_units = [source_to_units[child][0] for child in child_ids if source_to_units.get(child)]
        source_to_units[parent].extend(child_units)

    final_units: Dict[str, Dict[str, Any]] = {}
    decisions: List[Dict[str, Any]] = []
    covered_default: Set[str] = set()
    lineage: Dict[str, str] = {}
    ext_referenced: Set[str] = set()

    for entry in reviewed:
        entry_sources = entry.sources
        if not entry_sources:
            raise ValueError(f"{entry.entry_id}: no sources")
        for sid in entry_sources:
            if sid not in records:
                raise ValueError(f"{entry.entry_id}: unknown source {sid}")

        ext_sources = [sid for sid in entry_sources if records[sid]["role"] == "extension_surface"]
        other_sources = [sid for sid in entry_sources if records[sid]["role"] != "extension_surface"]
        if ext_sources and other_sources:
            raise ValueError(f"{entry.entry_id}: mixes extension and non-extension sources")

        status = "REVIEWED" if entry.status == "accepted" else "PENDING"
        proposal = None
        disposition = None
        rationale = entry.fields.get("rationale", "")
        if status == "REVIEWED":
            disposition = entry.fields.get("disposition")
            if not disposition:
                raise ValueError(f"{entry.entry_id}: reviewed entry lacks disposition")
        else:
            proposal = entry.fields.get("proposal") or entry.fields.get("disposition")
            if not entry.fields.get("reason") or not entry.fields.get("blocker"):
                raise ValueError(f"{entry.entry_id}: pending entry lacks reason/blocker")

        if ext_sources:
            unit_id = "RU." + entry.entry_id
            final_units[unit_id] = _unit(
                unit_id,
                "fabric_extension",
                [],
                [],
                status,
                title=entry.fields.get("title", entry.entry_id),
                disposition=disposition,
                rationale=rationale,
                proposal=proposal,
                reason=entry.fields.get("reason"),
                blocker=entry.fields.get("blocker"),
                reviewed_entry=entry.entry_id,
                extension_sources=ext_sources,
            )
            ext_referenced.update(ext_sources)
            decisions.append(
                {
                    "decisionId": "ND-GROUP-" + entry.entry_id,
                    "operation": "GROUP",
                    "affectedSourceIds": sorted(ext_sources),
                    "unitsBefore": [],
                    "unitsAfter": [unit_id],
                    "mechanicalDefault": "extension surfaces have no default semantic review unit",
                    "selected": "one reviewed grouping over the cited extension signatures",
                    "rationale": entry.fields.get("rationale", ""),
                    "context": MODEL_FORMAT,
                }
            )
            continue

        mapped_units: Set[str] = set()
        entry_variant_ids = {
            sid for sid in other_sources if records[sid]["recordType"] == "action_variant"
        }
        for sid in other_sources:
            record = records[sid]
            if record["recordType"] == "packet_envelope":
                children_for_entry = children.get(sid, [])
                selected = [child for child in children_for_entry if child in entry_variant_ids]
                if not selected:
                    selected = children_for_entry
                units = [
                    source_to_units[child][0]
                    for child in selected
                    if source_to_units.get(child)
                ]
            else:
                units = source_to_units.get(sid)
            if not units:
                raise ValueError(f"{entry.entry_id}: source {sid} has no default unit")
            mapped_units.update(units)

        family = records[other_sources[0]]["family"]
        if any(records[sid]["family"] != family for sid in other_sources):
            raise ValueError(f"{entry.entry_id}: mixes source families")

        candidates: Set[str] = set()
        evidence_ids: Set[str] = set()
        for default_id in mapped_units:
            default = default_units.get(default_id)
            if default is None:
                raise ValueError(f"{entry.entry_id}: default unit already consumed: {default_id}")
            candidates.update(default["candidateSourceIds"])
            evidence_ids.update(default["evidenceSourceIds"])
            covered_default.add(default_id)

        unit_id = "RU." + entry.entry_id
        final_units[unit_id] = _unit(
            unit_id,
            family,
            candidates,
            evidence_ids,
            status,
            title=entry.fields.get("title", entry.entry_id),
            disposition=disposition,
            rationale=rationale,
            proposal=proposal,
            reason=entry.fields.get("reason"),
            blocker=entry.fields.get("blocker"),
            reviewed_entry=entry.entry_id,
        )
        for default_id in mapped_units:
            lineage[default_id] = unit_id

        if len(mapped_units) > 1:
            decisions.append(
                {
                    "decisionId": "ND-MERGE-" + entry.entry_id,
                    "operation": "MERGE",
                    "affectedSourceIds": sorted(
                        sid for default_id in mapped_units for sid in default_units[default_id]["candidateSourceIds"]
                    ),
                    "unitsBefore": sorted(mapped_units),
                    "unitsAfter": [unit_id],
                    "mechanicalDefault": "one default unit per candidate source",
                    "selected": f"{len(mapped_units)} default units grouped into one semantic review unit",
                    "rationale": "The reviewed entry records one semantic decision over these source-level candidates; "
                    "their source identities are preserved in the unit.",
                    "context": MODEL_FORMAT,
                }
            )

    for unit_id, unit in default_units.items():
        if unit_id in covered_default:
            continue
        final_units[unit_id] = unit
        lineage[unit_id] = unit_id

    # Every candidate must be in exactly one final unit.
    candidate_ids = {sid for sid, record in records.items() if record["role"] == "candidate"}
    unit_candidates: Dict[str, List[str]] = {}
    for unit in final_units.values():
        for sid in unit["candidateSourceIds"]:
            unit_candidates.setdefault(sid, []).append(unit["unitId"])
    missing_candidates = sorted(candidate_ids - set(unit_candidates))
    if missing_candidates:
        raise ValueError(f"candidates lost during normalization: {missing_candidates[:5]}")
    duplicates = sorted(sid for sid, units in unit_candidates.items() if len(units) > 1)
    if duplicates:
        raise ValueError(f"candidates in multiple units: {duplicates[:5]}")

    # Every evidence record must be referenced by a unit; report orphans rather
    # than dropping them.
    evidence_ids = {sid for sid, record in records.items() if record["role"] == "evidence"} | set(evidence)
    referenced_evidence: Set[str] = set()
    for unit in final_units.values():
        referenced_evidence.update(unit["evidenceSourceIds"])
    orphan_evidence = sorted(evidence_ids - referenced_evidence)
    if orphan_evidence:
        raise ValueError(f"evidence not linked to any unit: {orphan_evidence[:5]}")

    # Extension surfaces are an inventory; only reviewed groupings become units.
    scope_exclusions = [
        {
            "sourceId": sid,
            "reason": "Extension-surface inventory; no semantic review unit in Round 0.",
            "decisionId": "ND-SCOPE-EXT",
        }
        for sid in sorted(extension_ids)
        if sid not in ext_referenced
    ]

    # Remove transient envelope source IDs from unit evidence? They are evidence
    # records and must remain referenced; keep them.
    final_unit_list = sorted(final_units.values(), key=lambda unit: unit["unitId"])

    by_family_status: Dict[str, Dict[str, int]] = defaultdict(lambda: {"REVIEWED": 0, "PENDING": 0})
    for unit in final_unit_list:
        by_family_status[unit["family"]][unit["status"]] += 1

    counts = {
        "generatedSourceRecords": len(sources),
        "sourceRecords": len(records) + len(evidence),
        "roles": {
            "candidate": sum(1 for record in records.values() if record["role"] == "candidate"),
            "evidence": sum(1 for record in records.values() if record["role"] == "evidence") + len(evidence),
            "extension_surface": sum(1 for record in records.values() if record["role"] == "extension_surface"),
        },
        "reviewUnits": len(final_unit_list),
        "reviewUnitsByFamily": dict(
            sorted((family, sum(1 for unit in final_unit_list if unit["family"] == family))
                   for family in {unit["family"] for unit in final_unit_list})
        ),
        "reviewStatus": {
            "REVIEWED": sum(1 for unit in final_unit_list if unit["status"] == "REVIEWED"),
            "PENDING": sum(1 for unit in final_unit_list if unit["status"] == "PENDING"),
        },
        "reviewUnitsByFamilyStatus": {
            family: dict(statuses) for family, statuses in sorted(by_family_status.items())
        },
        "candidates": len(candidate_ids),
        "candidatesLinked": len(candidate_ids) - len(missing_candidates),
        "evidence": len(evidence_ids),
        "derivedEvidence": len(evidence),
        "evidenceLinked": len(referenced_evidence),
        "evidenceOrphans": len(orphan_evidence),
        "extensionSurfaces": len(extension_ids),
        "extensionSurfacesLinked": len(ext_referenced),
        "extensionSurfacesScopeExcluded": len(scope_exclusions),
        "gaps": 0,
        "clientboundReviewUnits": sum(1 for unit in final_unit_list if unit["family"] == "clientbound_play"),
        "clientboundPending": sum(
            1 for unit in final_unit_list
            if unit["family"] == "clientbound_play" and unit["status"] == "PENDING"
        ),
    }

    model = {
        "format": MODEL_FORMAT,
        "generatedFrom": "completeness.generated.json",
        "generatedSha256": sha256_file(Path(generated["_path"])) if "_path" in generated else "",
        "reviewedFrom": "completeness.reviewed.md",
        "reviewedSha256": generated.get("_reviewed_sha256", ""),
        "rules": {
            "roleAssignment": {
                "serverbound_packet_without_discriminator": "candidate",
                "serverbound_packet_with_discriminator": "evidence (packet envelope); discriminator values are candidates",
                "serverbound_action_variant": "candidate",
                "clientbound_packet": "candidate (Round 3 pending)",
                "passive_embodied_method": "candidate",
                "fabric_extension_signature": "extension_surface",
                "handler_method_calls_fingerprint": "evidence",
                "clientbound_class_fingerprint": "evidence",
                "passive_method_facts": "evidence",
            },
            "defaultUnit": "one unit per candidate; action/containerInput values split; packet envelope is evidence",
            "extensionSurfaces": "extension inventory, not candidates; only cited groupings become review units",
            "fingerprints": "evidence review triggers, not semantic proof",
        },
        "sources": sorted(list(records.values()) + list(evidence.values()), key=lambda item: item["sourceId"]),
        "defaultUnits": sorted(default_units.values(), key=lambda unit: unit["unitId"]),
        "reviewUnits": final_unit_list,
        "unitLineage": dict(sorted(lineage.items())),
        "scopeExclusions": scope_exclusions,
        "gaps": [],
        "counts": counts,
    }

    # Review-level scope decisions are appended after the mechanical unit model.
    decisions.insert(0, {
        "decisionId": "ND-RULE-ROLES",
        "operation": "RULE",
        "affectedSourceIds": [],
        "unitsBefore": [],
        "unitsAfter": [],
        "mechanicalDefault": "record type only",
        "selected": "deterministic role table documented in completeness.normalization.json",
        "rationale": "Roles are assigned from source family/kind, not Java names or existing primitives.",
        "context": MODEL_FORMAT,
    })
    decisions.insert(1, {
        "decisionId": "ND-RULE-SPLIT-ACTION",
        "operation": "SPLIT",
        "affectedSourceIds": sorted(
            sid for sid, record in records.items()
            if record["recordType"] == "action_variant"
        ),
        "unitsBefore": [],
        "unitsAfter": sorted(
            "U." + sid for sid, record in records.items()
            if record["recordType"] == "action_variant"
        ),
        "mechanicalDefault": "one candidate unit per serverbound packet",
        "selected": "separate candidate units per action/containerInput value; envelope is evidence",
        "rationale": "Explicit action discriminators can represent distinct operations; the split is provisional and reviewable.",
        "context": MODEL_FORMAT,
    })
    decisions.insert(2, {
        "decisionId": "ND-CB-WITHDRAW",
        "operation": "RECLASSIFY",
        "affectedSourceIds": sorted(
            sid for sid, record in records.items() if record["family"] == "clientbound_play"
        ),
        "unitsBefore": sorted(
            unit["unitId"] for unit in default_units.values() if unit["family"] == "clientbound_play"
        ),
        "unitsAfter": sorted(
            unit["unitId"] for unit in final_unit_list if unit["family"] == "clientbound_play"
        ),
        "mechanicalDefault": "previously EXCLUDED from the robot observation contract",
        "selected": "withdrawn; clientbound candidates are PENDING Round 3 except explicitly reviewed protocol units",
        "rationale": "The blanket exclusion was not justified by evidence. Round 3 must review observation and self-state semantics; "
        "presentation/UI entries may still be excluded after review.",
        "context": MODEL_FORMAT,
    })
    decisions.insert(3, {
        "decisionId": "ND-SCOPE-EXT",
        "operation": "SCOPE",
        "affectedSourceIds": sorted(item["sourceId"] for item in scope_exclusions),
        "unitsBefore": [],
        "unitsAfter": [],
        "mechanicalDefault": "every extension signature becomes a review unit",
        "selected": "extension signatures are an extension_surface inventory; only cited capability groupings become units",
        "rationale": "Extension points are context for capability families; per-signature units would manufacture semantic decisions.",
        "context": MODEL_FORMAT,
    })
    pending_reviewed = [
        unit for unit in final_unit_list
        if unit["status"] == "PENDING" and unit.get("reviewedEntry")
    ]
    if pending_reviewed:
        decisions.append({
            "decisionId": "ND-PENDING-REVIEWS",
            "operation": "RECLASSIFY",
            "affectedSourceIds": sorted(
                sid for unit in pending_reviewed
                for sid in unit["candidateSourceIds"] + unit["extensionSourceIds"]
            ),
            "unitsBefore": [],
            "unitsAfter": sorted(unit["unitId"] for unit in pending_reviewed),
            "mechanicalDefault": "existing entries carried a provisional disposition with status=proposed",
            "selected": "status=PENDING with a separate provisional proposal, reason and blocker",
            "rationale": "A pending unit must not carry a final disposition; provisional outcomes are retained only as proposals.",
            "context": MODEL_FORMAT,
        })

    ledger = {
        "format": DECISION_FORMAT,
        "model": "completeness.normalized.json",
        "generated": "completeness.generated.json",
        "reviewed": "completeness.reviewed.md",
        "generatedSha256": model["generatedSha256"],
        "reviewedSha256": model["reviewedSha256"],
        "rules": model["rules"],
        "decisions": decisions,
    }
    return model, ledger


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Normalize the primitive audit inventory")
    parser.add_argument("--generated", default="docs/auditing/completeness.generated.json")
    parser.add_argument("--reviewed", default="docs/auditing/completeness.reviewed.md")
    parser.add_argument("--model", default="docs/auditing/completeness.normalized.json")
    parser.add_argument("--decisions", default="docs/auditing/completeness.normalization.json")
    args = parser.parse_args(argv)

    generated_path = Path(args.generated)
    reviewed_path = Path(args.reviewed)
    generated = json.loads(generated_path.read_text(encoding="utf-8"))
    reviewed = parse_reviewed(reviewed_path)
    model, ledger = normalize(generated, reviewed)
    model["generatedSha256"] = sha256_file(generated_path)
    model["reviewedSha256"] = sha256_file(reviewed_path)
    ledger["generatedSha256"] = model["generatedSha256"]
    ledger["reviewedSha256"] = model["reviewedSha256"]

    write_json_lf(Path(args.model), model)
    write_json_lf(Path(args.decisions), ledger)
    counts = model["counts"]
    print(
        "normalized: sources={sourceRecords} candidates={candidates} evidence={evidence} "
        "extension_surfaces={extension_surfaces} units={units} reviewed={reviewed} pending={pending}".format(
            sourceRecords=counts["sourceRecords"],
            candidates=counts["roles"]["candidate"],
            evidence=counts["roles"]["evidence"],
            extension_surfaces=counts["roles"]["extension_surface"],
            units=counts["reviewUnits"],
            reviewed=counts["reviewStatus"]["REVIEWED"],
            pending=counts["reviewStatus"]["PENDING"],
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
