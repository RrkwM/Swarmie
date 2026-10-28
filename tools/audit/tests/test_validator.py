from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

AUDIT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(AUDIT_ROOT))

from validate_completeness import (  # noqa: E402
    ReviewedEntry,
    load_generated,
    main,
    nondeterminism,
    parse_reviewed,
    validate,
)

HASH = "a" * 64


def source(source_id: str, fingerprint: str = HASH, parent: str | None = None) -> dict:
    item = {
        "sourceId": source_id,
        "category": "test",
        "kind": "packet_variant" if parent else "packet",
        "fingerprint": fingerprint,
        "handler": {
            "calls": [
                "INVOKEVIRTUAL net.minecraft.server.level.ServerPlayer.attack"
                "(Lnet/minecraft/world/entity/Entity;)V"
            ]
        },
    }
    if parent:
        item["parentId"] = parent
    return item


def generated(*items: dict) -> dict:
    return {"format": "test/1", "sources": list(items)}


def reviewed(*entries: ReviewedEntry) -> list[ReviewedEntry]:
    return list(entries)


def entry(
    entry_id: str,
    sources: str,
    fingerprint: str | None = None,
    status: str = "accepted",
    dispatch: str | None = None,
    removal: str | None = None,
) -> ReviewedEntry:
    fields = {
        "title": entry_id,
        "sources": sources,
        "disposition": "MAPPED",
        "domain": "blocks",
        "operation": "test",
        "temporal": "ACTION",
        "rationale": "fixture",
        "status": status,
    }
    if fingerprint is not None:
        fields["fingerprint"] = fingerprint
    if dispatch is not None:
        fields["dispatch"] = dispatch
    if removal is not None:
        fields["removal"] = removal
    return ReviewedEntry(entry_id, 1, fields)


class ValidateTest(unittest.TestCase):
    def test_clean_catalogue_has_no_errors(self):
        findings = validate(
            generated(source("A")),
            reviewed(entry("SEM-A", "A", f"A={HASH}")),
        )
        self.assertFalse([finding for finding in findings if finding.level == "error"])
        self.assertFalse([finding for finding in findings if finding.code == "UNREVIEWED_SOURCES"])

    def test_unreviewed_source_is_visible(self):
        findings = validate(
            generated(source("A"), source("B")),
            reviewed(entry("SEM-A", "A", f"A={HASH}")),
        )
        unreviewed = [finding for finding in findings if finding.code == "UNREVIEWED_SOURCES"]
        self.assertEqual(len(unreviewed), 1)
        self.assertIn("B", unreviewed[0].message)
        self.assertEqual(unreviewed[0].level, "review")

    def test_fingerprint_change_requires_review(self):
        findings = validate(
            generated(source("A")),
            reviewed(entry("SEM-A", "A", f"A={'b' * 64}")),
        )
        changed = [finding for finding in findings if finding.code == "FINGERPRINT_CHANGED"]
        self.assertEqual(len(changed), 1)
        self.assertEqual(changed[0].level, "review")

    def test_invalid_source_reference_is_an_error(self):
        findings = validate(
            generated(source("A")),
            reviewed(entry("SEM-A", "MISSING", f"MISSING={HASH}")),
        )
        self.assertTrue(any(finding.code == "UNKNOWN_SOURCE" and finding.level == "error"
                            for finding in findings))

    def test_disappeared_source_is_an_error(self):
        old = generated(source("A"), source("B"))
        new = generated(source("A"))
        findings = validate(
            new,
            reviewed(entry("SEM-A", "A", f"A={HASH}")),
            previous=old,
        )
        self.assertTrue(any(finding.code == "DISAPPEARED_SOURCE" and finding.level == "error"
                            for finding in findings))

    def test_confirmed_removal_is_not_an_error(self):
        old = generated(source("A"), source("B"))
        new = generated(source("A"))
        findings = validate(
            new,
            reviewed(
                entry("SEM-A", "A", f"A={HASH}"),
                entry("SEM-B", "B", status="retired", removal="confirmed"),
            ),
            previous=old,
        )
        self.assertFalse([finding for finding in findings if finding.level == "error"])
        self.assertTrue(any(finding.code == "REMOVAL_CONFIRMED" for finding in findings))

    def test_new_source_is_reviewed(self):
        old = generated(source("A"))
        new = generated(source("A"), source("B"))
        findings = validate(
            new,
            reviewed(entry("SEM-A", "A", f"A={HASH}")),
            previous=old,
        )
        self.assertTrue(any(finding.code == "NEW_SOURCE" and finding.source == "B"
                            for finding in findings))

    def test_dispatch_change_requires_review(self):
        findings = validate(
            generated(source("A")),
            reviewed(entry("SEM-A", "A", f"A={HASH}", dispatch="A=SomeOther.call")),
        )
        changed = [finding for finding in findings if finding.code == "DISPATCH_CHANGED"]
        self.assertEqual(len(changed), 1)
        self.assertEqual(changed[0].level, "review")

    def test_missing_reviewed_field_is_an_error(self):
        broken = ReviewedEntry("SEM-X", 1, {"sources": "A", "disposition": "MAPPED",
                                            "status": "accepted"})
        findings = validate(generated(source("A")), reviewed(broken))
        self.assertTrue(any(finding.code == "INVALID_ENTRY" and finding.level == "error"
                            for finding in findings))

    def test_parent_packet_covers_its_variants(self):
        findings = validate(
            generated(source("A"), source("A.X", parent="A")),
            reviewed(entry("SEM-A", "A", f"A={HASH}")),
        )
        self.assertFalse(any(finding.code == "UNREVIEWED_SOURCES" for finding in findings))

    def test_nondeterminism_is_an_error(self):
        different = generated(source("A", "c" * 64))
        findings = nondeterminism(generated(source("A")), different)
        self.assertTrue(any(finding.code == "NONDETERMINISM" and finding.level == "error"
                            for finding in findings))

    def test_identical_runs_are_reproducible(self):
        self.assertFalse(nondeterminism(generated(source("A")), generated(source("A"))))

    def test_reviewed_markdown_parser(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "reviewed.md"
            path.write_text(
                "# header\n\n## SEM-A\n- sources: A, B\n- fingerprint: A=" + HASH
                + "\n- status: accepted\n",
                encoding="utf-8",
            )
            entries = parse_reviewed(path)
            self.assertEqual(len(entries), 1)
            self.assertEqual(entries[0].sources, ["A", "B"])
            self.assertEqual(entries[0].fields["fingerprint"], "A=" + HASH)


class CliTest(unittest.TestCase):
    def test_strict_fails_on_review_findings_but_default_does_not(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            generated_path = root / "generated.json"
            reviewed_path = root / "reviewed.md"
            generated_path.write_text(json.dumps(generated(source("A"), source("B"))), encoding="utf-8")
            reviewed_path.write_text(
                "## SEM-A\n- sources: A\n- disposition: MAPPED\n- domain: blocks\n"
                "- operation: test\n- temporal: ACTION\n- rationale: fixture\n"
                f"- fingerprint: A={HASH}\n- status: accepted\n",
                encoding="utf-8",
            )
            self.assertEqual(main(["--generated", str(generated_path),
                                   "--reviewed", str(reviewed_path)]), 0)
            self.assertEqual(main(["--generated", str(generated_path),
                                   "--reviewed", str(reviewed_path), "--strict"]), 1)


if __name__ == "__main__":
    unittest.main()
