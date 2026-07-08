"""Tests for scripts/forensic/validate.py — force-authorisation pre-validation."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from scripts.forensic.validate import (
    FORCE_AUTH_REASONS,
    find_evidence_files,
    run_pre_checks,
    validate_bundle_root,
    validate_evidence_lifecycle,
)


def _make_bundle(has_proto: bool, force_auth_hash: str = "abc123def456",
                 consumed_hash: str = "consumed789") -> dict:
    """Build a minimal bundle root dict for testing."""
    bundle = {
        "bundle/schema-version": "bundle-root.v1",
        "bundle/hash": "test-hash",
        "execution/summary": {"status": "pass", "totals": {"passed": 1, "failed": 0, "total": 1}},
        "registry/snapshot": {"attestor-registry-hash": "reg1"},
    }
    if has_proto:
        bundle["protocol/state-hashes"] = {
            "force-authorisations/hash": force_auth_hash,
            "force-authorisations/consumed-hash": consumed_hash,
        }
    return bundle


class TestValidateBundleRoot:
    def test_skips_when_no_protocol_state(self):
        bundle = _make_bundle(False)
        checks = validate_bundle_root(bundle)
        assert len(checks) == 1
        assert checks[0]["check/key"] == "protocol-state-hashes-present"
        assert checks[0]["check/status"] == "skip"

    def test_passes_with_valid_hashes(self):
        bundle = _make_bundle(True)
        checks = validate_bundle_root(bundle)
        assert len(checks) == 3
        assert checks[0]["check/status"] == "pass"
        assert checks[1]["check/status"] == "pass"
        assert checks[2]["check/status"] == "pass"

    def test_fails_when_fa_hash_empty(self):
        bundle = _make_bundle(True, force_auth_hash="")
        checks = validate_bundle_root(bundle)
        fa_check = [c for c in checks if "hash-well-formed" in c["check/key"]
                     and "force-authorisations" in c["check/key"]
                     and "consumed" not in c["check/key"]][0]
        assert fa_check["check/status"] == "fail"

    def test_fails_when_consumed_hash_empty(self):
        bundle = _make_bundle(True, consumed_hash="")
        checks = validate_bundle_root(bundle)
        consumed_check = [c for c in checks if "consumed" in c["check/key"]][0]
        assert consumed_check["check/status"] == "fail"


class TestFindEvidenceFiles:
    def test_returns_empty_when_no_event_dir(self, tmp_path):
        assert find_evidence_files(tmp_path) == []

    def test_finds_force_auth_events(self, tmp_path):
        ev_dir = tmp_path / "event-evidence"
        ev_dir.mkdir()
        ev = {"evidence/type": "force-authorisation-granted",
              "force-auth/auth-id": "fa-0", "event/seq": 0}
        (ev_dir / "grant.json").write_text(json.dumps(ev))
        result = find_evidence_files(tmp_path)
        assert len(result) == 1
        assert result[0]["type"] == "force-authorisation-granted"

    def test_skips_non_force_auth_events(self, tmp_path):
        ev_dir = tmp_path / "event-evidence"
        ev_dir.mkdir()
        ev = {"evidence/type": "escrow-released", "event/seq": 0}
        (ev_dir / "release.json").write_text(json.dumps(ev))
        assert find_evidence_files(tmp_path) == []


class TestValidateEvidenceLifecycle:
    def test_empty_events_returns_no_checks(self):
        assert validate_evidence_lifecycle([]) == []

    def test_grant_then_execute_passes(self):
        events = [
            {"type": "force-authorisation-granted", "auth-id": "fa-0", "seq": 0},
            {"type": "force-authorisation-executed", "auth-id": "fa-0", "seq": 1},
        ]
        checks = validate_evidence_lifecycle(events)
        fails = [c for c in checks if c["check/status"] == "fail"]
        assert len(fails) == 0

    def test_execute_without_grant_fails(self):
        events = [
            {"type": "force-authorisation-executed", "auth-id": "fa-0", "seq": 0},
        ]
        checks = validate_evidence_lifecycle(events)
        fails = [c for c in checks if c["check/status"] == "fail"]
        assert len(fails) == 1
        assert "execute-without-grant" in fails[0]["check/key"]

    def test_double_execute_fails(self):
        events = [
            {"type": "force-authorisation-granted", "auth-id": "fa-0", "seq": 0},
            {"type": "force-authorisation-executed", "auth-id": "fa-0", "seq": 1},
            {"type": "force-authorisation-executed", "auth-id": "fa-0", "seq": 2},
        ]
        checks = validate_evidence_lifecycle(events)
        fails = [c for c in checks if c["check/status"] == "fail"]
        assert len(fails) == 1
        assert "double-execute" in fails[0]["check/key"]


class TestRunPreChecks:
    def test_pass_with_proto_and_no_evidence(self, tmp_path):
        bundle = _make_bundle(True)
        bundle_path = tmp_path / "bundle.json"
        bundle_path.write_text(json.dumps(bundle))
        report = run_pre_checks(bundle_path)
        assert report["validate/status"] == "pass"

    def test_fail_when_evidence_but_no_proto(self, tmp_path):
        bundle = _make_bundle(False)
        bundle_path = tmp_path / "bundle.json"
        bundle_path.write_text(json.dumps(bundle))
        ev_dir = tmp_path / "event-evidence"
        ev_dir.mkdir()
        ev = {"evidence/type": "force-authorisation-granted",
              "force-auth/auth-id": "fa-0", "event/seq": 0}
        (ev_dir / "grant.json").write_text(json.dumps(ev))
        report = run_pre_checks(bundle_path, run_dir=tmp_path)
        assert report["validate/status"] == "fail"
        fails = [c for c in report["validate/checks"] if c["check/status"] == "fail"]
        assert any("force-auth-evidence-without-state-hashes" in c["check/key"] for c in fails)

    def test_pass_with_evidence_and_proto(self, tmp_path):
        bundle = _make_bundle(True)
        bundle_path = tmp_path / "bundle.json"
        bundle_path.write_text(json.dumps(bundle))
        ev_dir = tmp_path / "event-evidence"
        ev_dir.mkdir()
        ev = {"evidence/type": "force-authorisation-granted",
              "force-auth/auth-id": "fa-0", "event/seq": 0}
        (ev_dir / "grant.json").write_text(json.dumps(ev))
        report = run_pre_checks(bundle_path, run_dir=tmp_path)
        assert report["validate/status"] == "pass"
