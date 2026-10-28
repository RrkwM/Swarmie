#!/usr/bin/env python3
"""Compare the generated primitive inventory with the reviewed catalogue.

The validator is deliberately advisory by default: structural errors fail, but
unreviewed sources and changed fingerprints are reported as review findings
(exit 0) unless --strict is passed. It is not wired into CI as a completeness
gate.
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Set, Tuple

DISPOSITIONS = {"MAPPED", "PRIVILEGED", "EXCLUDED", "CONTENT", "PROTOCOL_MECHANISM"}
TEMPORAL = {"QUERY", "SETPOINT", "ACTION", "n/a"}
STATUSES = {"accepted", "proposed", "blocked", "retired"}
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

    def __str__(self) -> str:
        where = f" [{self.entry}]" if self.entry else ""
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
        for required in ("disposition", "status"):
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

    # A reviewed packet covers its action-value children unless they are split out.
    covered = set(reviewed_sources)
    changed = True
    while changed:
        changed = False
        for source_id, source in sources.items():
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


def nondeterminism(a: Dict[str, Any], b: Dict[str, Any]) -> List[Finding]:
    """A repeat run over unchanged inputs must be byte-for-byte equivalent."""
    if json.dumps(a, sort_keys=True) != json.dumps(b, sort_keys=True):
        return [Finding("error", "NONDETERMINISM",
                        "two runs over identical inputs produced different inventories")]
    return []


def summarize(generated: Dict[str, Any], reviewed: List[ReviewedEntry]) -> str:
    sources = generated["sources"]
    reviewed_ids = {source_id for entry in reviewed for source_id in entry.sources}
    covered = set(reviewed_ids)
    changed = True
    while changed:
        changed = False
        for source in sources:
            source_id = source.get("sourceId")
            if source_id not in covered and source.get("parentId") in covered:
                covered.add(source_id)
                changed = True
    return (
        f"sources={len(sources)} reviewed_entries={len(reviewed)} "
        f"reviewed_sources={len(reviewed_ids)} covered={len(covered)} "
        f"unreviewed={max(0, len(sources) - len(covered))}"
    )


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Validate primitive completeness artifacts")
    parser.add_argument("--generated", required=True)
    parser.add_argument("--reviewed", required=True)
    parser.add_argument("--previous", help="previous generated inventory for new/disappeared detection")
    parser.add_argument("--repeat", help="second generated inventory from the same inputs")
    parser.add_argument("--strict", action="store_true", help="treat review findings as failures")
    parser.add_argument("--list-unreviewed", type=int, default=20)
    parser.add_argument("--json-report")
    args = parser.parse_args(argv)

    generated = load_generated(args.generated)
    reviewed = parse_reviewed(args.reviewed)
    previous = load_generated(args.previous) if args.previous else None
    findings = validate(generated, reviewed, previous, args.list_unreviewed)

    if args.repeat:
        repeat = load_generated(args.repeat)
        findings.extend(nondeterminism(generated, repeat))

    print(summarize(generated, reviewed))
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
