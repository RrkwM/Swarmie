from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path

AUDIT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(AUDIT_ROOT))

from normalize_audit import normalize  # noqa: E402
from validate_completeness import ReviewedEntry, validate_model  # noqa: E402

HASH = "a" * 64


def packet(source_id, family, kind="packet", parent=None):
    item = {
        "sourceId": source_id,
        "category": family,
        "kind": kind,
        "class": "example." + source_id.replace(".", "_"),
        "fingerprint": HASH,
        "fingerprintKind": "class_sha256",
        "fields": [],
        "handler": {
            "method": "handleExample",
            "implementation": "example.Listener",
            "found": True,
            "calls": ["INVOKEVIRTUAL example.Listener.handleExample()V"],
            "fingerprint": HASH,
        },
    }
    if parent:
        item["parentId"] = parent
    return item


def passive(source_id):
    return {
        "sourceId": source_id,
        "category": "passive_embodied",
        "kind": "method",
        "class": "example.Entity",
        "method": "tick",
        "descriptor": "()V",
        "found": True,
        "fingerprint": HASH,
        "invokedynamicCount": 0,
        "calls": [],
    }


def extension(source_id):
    return {
        "sourceId": source_id,
        "category": "fabric_extension",
        "kind": "method",
        "domain": "networking",
        "class": "example.Api",
        "method": "register",
        "descriptor": "()V",
        "found": True,
        "fingerprint": HASH,
    }


def reviewed(entry_id, sources, status="accepted", disposition=None, proposal=None,
             reason=None, blocker=None):
    fields = {"title": entry_id, "sources": sources, "status": status, "rationale": "fixture"}
    if disposition:
        fields["disposition"] = disposition
    if proposal:
        fields["proposal"] = proposal
    if reason:
        fields["reason"] = reason
    if blocker:
        fields["blocker"] = blocker
    return ReviewedEntry(entry_id, 1, fields)


def fixture():
    generated = {
        "format": "test/1",
        "sources": [
            packet("SB.PLAYER", "serverbound_play"),
            packet("SB.PLAYER.ACTION.USE", "serverbound_play", "packet_variant", "SB.PLAYER"),
            packet("CB.STATE", "clientbound_play"),
            passive("PAS.Entity.tick#abc"),
            extension("EXT.FABRIC.API.register#abc"),
        ],
    }
    catalogue = [
        reviewed("SEM-1", "SB.PLAYER.ACTION.USE", disposition="MAPPED"),
        reviewed("SEM-2", "CB.STATE", status="pending", proposal="EXCLUDED",
                 reason="deferred", blocker="Round 3"),
        reviewed("SEM-3", "EXT.FABRIC.API.register#abc", disposition="PROTOCOL_MECHANISM"),
    ]
    return generated, catalogue


class NormalizeTest(unittest.TestCase):
    def test_roles_units_and_counts(self):
        generated, catalogue = fixture()
        model, ledger = normalize(generated, catalogue)

        roles = {source["sourceId"]: source["role"] for source in model["sources"]}
        self.assertEqual(roles["SB.PLAYER"], "evidence")
        self.assertEqual(roles["SB.PLAYER.ACTION.USE"], "candidate")
        self.assertEqual(roles["CB.STATE"], "candidate")
        self.assertEqual(roles["PAS.Entity.tick#abc"], "candidate")
        self.assertEqual(roles["EXT.FABRIC.API.register#abc"], "extension_surface")

        self.assertEqual(model["counts"]["roles"]["candidate"], 3)
        self.assertEqual(model["counts"]["roles"]["evidence"], 4)  # envelope + 3 derived
        self.assertEqual(model["counts"]["roles"]["extension_surface"], 1)
        self.assertEqual(model["counts"]["reviewUnits"], 4)
        self.assertEqual(model["counts"]["reviewStatus"], {"REVIEWED": 2, "PENDING": 2})

        units = {unit["unitId"]: unit for unit in model["reviewUnits"]}
        self.assertIn("RU.SEM-1", units)
        self.assertEqual(units["RU.SEM-1"]["candidateSourceIds"], ["SB.PLAYER.ACTION.USE"])
        self.assertIn("SB.PLAYER", units["RU.SEM-1"]["evidenceSourceIds"])
        self.assertEqual(units["RU.SEM-2"]["status"], "PENDING")
        self.assertIsNone(units["RU.SEM-2"]["disposition"])
        self.assertEqual(units["RU.SEM-2"]["proposal"], "EXCLUDED")
        self.assertIn("U.PAS.Entity.tick#abc", units)

        decisions = {item["decisionId"] for item in ledger["decisions"]}
        self.assertIn("ND-CB-WITHDRAW", decisions)
        self.assertIn("ND-PENDING-REVIEWS", decisions)
        self.assertIn("ND-GROUP-SEM-3", decisions)
        self.assertEqual(validate_model(generated, model, ledger), [])

    def test_fingerprint_source_identity_is_preserved(self):
        generated, catalogue = fixture()
        model, _ = normalize(generated, catalogue)
        for unit in model["reviewUnits"]:
            for source_id in unit["candidateSourceIds"]:
                self.assertTrue(any(source["sourceId"] == source_id for source in model["sources"]))


class ModelInvariantTest(unittest.TestCase):
    def setUp(self):
        self.generated, catalogue = fixture()
        self.model, self.ledger = normalize(self.generated, catalogue)
        self.model = copy.deepcopy(self.model)
        self.ledger = copy.deepcopy(self.ledger)

    def codes(self, model=None, ledger=None):
        return [finding.code for finding in validate_model(
            self.generated, model or self.model, ledger or self.ledger)]

    def test_candidate_omitted_from_all_units(self):
        unit = next(item for item in self.model["reviewUnits"] if item["unitId"] == "RU.SEM-1")
        unit["candidateSourceIds"] = []
        self.assertIn("CANDIDATE_OMITTED", self.codes())

    def test_evidence_without_link_or_orphan_reason(self):
        self.model["sources"].append({
            "sourceId": "EV.ORPHAN", "role": "evidence", "family": "serverbound_play",
            "recordType": "handler_method", "generatedSourceId": "SB.PLAYER",
        })
        self.model.pop("counts", None)
        self.assertIn("EVIDENCE_ORPHANED", self.codes())

    def test_orphan_reason_allows_unreferenced_evidence(self):
        self.model["sources"].append({
            "sourceId": "EV.ORPHAN", "role": "evidence", "family": "serverbound_play",
            "recordType": "handler_method", "generatedSourceId": "SB.PLAYER",
        })
        self.model["orphanReasons"] = {"EV.ORPHAN": "kept for a future review family"}
        self.model.pop("counts", None)
        self.assertNotIn("EVIDENCE_ORPHANED", self.codes())

    def test_pending_without_reason_or_blocker(self):
        unit = next(item for item in self.model["reviewUnits"] if item["unitId"] == "RU.SEM-2")
        unit["reason"] = ""
        self.assertIn("PENDING_WITHOUT_REASON", self.codes())

    def test_pending_with_disposition(self):
        unit = next(item for item in self.model["reviewUnits"] if item["unitId"] == "RU.SEM-2")
        unit["disposition"] = "EXCLUDED"
        self.assertIn("PENDING_WITH_DISPOSITION", self.codes())

    def test_reviewed_without_disposition(self):
        unit = next(item for item in self.model["reviewUnits"] if item["unitId"] == "RU.SEM-1")
        unit["disposition"] = None
        self.assertIn("REVIEWED_WITHOUT_DISPOSITION", self.codes())

    def test_gap_points_to_unknown_unit(self):
        self.model["gaps"] = [{
            "unitId": "RU.NOPE", "behaviour": "b", "whyPublic": "p",
            "whyNotExisting": "n", "evidence": "e",
        }]
        self.assertIn("GAP_UNKNOWN_UNIT", self.codes())

    def test_gap_incomplete(self):
        self.model["gaps"] = [{"unitId": "RU.SEM-1", "behaviour": "b"}]
        self.assertIn("GAP_INCOMPLETE", self.codes())

    def test_decision_refers_to_missing_source(self):
        self.ledger["decisions"].append({
            "decisionId": "ND-BAD", "operation": "MERGE", "affectedSourceIds": ["NOPE"],
            "unitsBefore": [], "unitsAfter": [], "rationale": "bad",
        })
        self.assertIn("DECISION_UNKNOWN_SOURCE", self.codes())

    def test_decision_refers_to_missing_unit(self):
        self.ledger["decisions"].append({
            "decisionId": "ND-BAD", "operation": "MERGE", "affectedSourceIds": [],
            "unitsBefore": ["U.NOPE"], "unitsAfter": [], "rationale": "bad",
        })
        self.assertIn("DECISION_UNKNOWN_UNIT", self.codes())

    def test_candidate_in_two_units_with_conflicting_dispositions(self):
        original = next(item for item in self.model["reviewUnits"] if item["unitId"] == "RU.SEM-1")
        duplicate = copy.deepcopy(original)
        duplicate["unitId"] = "RU.SEM-1-DUP"
        duplicate["disposition"] = "PRIVILEGED"
        self.model["reviewUnits"].append(duplicate)
        codes = self.codes()
        self.assertIn("CANDIDATE_MULTI_UNIT", codes)
        self.assertIn("CONFLICTING_DISPOSITIONS", codes)

    def test_extension_surface_omitted(self):
        self.model["sources"].append({
            "sourceId": "EXT.ORPHAN", "role": "extension_surface",
            "family": "fabric_extension", "recordType": "extension_signature",
            "generatedSourceId": None,
        })
        self.model["scopeExclusions"] = []
        self.model.pop("counts", None)
        self.assertIn("EXTENSION_SURFACE_OMITTED", self.codes())

    def test_role_conflict_is_duplicate_source(self):
        self.model["sources"].append({
            "sourceId": "SB.PLAYER", "role": "candidate", "family": "serverbound_play",
            "recordType": "packet", "generatedSourceId": "SB.PLAYER",
        })
        self.assertIn("MODEL_SOURCE_DUPLICATE", self.codes())

    def test_stale_counts(self):
        self.model["counts"]["reviewUnits"] = 999
        self.assertIn("STALE_COUNTS", self.codes())

    def test_stale_generated_hash(self):
        findings = validate_model(
            self.generated, self.model, self.ledger, generated_sha256="deadbeef"
        )
        self.assertIn("STALE_MODEL", [finding.code for finding in findings])

    def test_valid_gap_is_accepted(self):
        self.model["gaps"] = [{
            "unitId": "RU.SEM-2",
            "behaviour": "required public behaviour",
            "whyPublic": "not privileged or content-specific",
            "whyNotExisting": "no existing primitive expresses it",
            "evidence": "SB.PLAYER.ACTION.USE",
            "scenario": "fixture scenario",
        }]
        self.model.pop("counts", None)
        codes = self.codes()
        self.assertFalse(any(code.startswith("GAP_") for code in codes))

    def test_clean_model_passes(self):
        self.assertEqual(self.codes(), [])


if __name__ == "__main__":
    unittest.main()
