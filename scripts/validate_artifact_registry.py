#!/usr/bin/env python3
"""Validate test artifact registry integrity + schema compatibility."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()


def load_json(path: Path):
    return json.loads(path.read_text())


def fail(msg: str):
    print(f"[artifact-registry] FAIL: {msg}")
    raise SystemExit(1)


def validate_schema_const(doc: dict, expected: str, label: str):
    actual = doc.get("schema_version")
    if actual != expected:
        fail(f"{label} schema_version mismatch: expected {expected}, got {actual}")


def validate_claimable_v2_integrity(claim: dict, raw_text: str) -> None:
    """Evidence-integrity checks for claimable-classification.v2 terminal observations."""
    if claim.get("schema_version") != "claimable-classification.v2":
        return

    classes = claim.get("classes") or {}
    for class_id, spec in classes.items():
        if "recipient_type" in spec and "recipient_types" not in spec:
            fail(f"claimable-classification.classes.{class_id} uses deprecated recipient_type")
        if "delivery_model" in spec and "governance-withdrawal" in str(spec.get("delivery_model", "")):
            fail(f"claimable-classification.classes.{class_id} mixes delivery_model with governance wording")

    obs = claim.get("terminal_observations") or {}
    fl = obs.get("funds_ledger") or {}
    if "claimable_total" in fl:
        fail("claimable-classification funds_ledger must not use deprecated claimable_total")
    by_token = fl.get("by_token") or {}
    if isinstance(by_token, dict):
        keys = list(by_token.keys())
        if len(keys) != len(set(keys)):
            fail("claimable-classification funds_ledger.by_token has duplicate token keys after parse")

    # Raw JSON must not contain duplicate keys in by_token (parser keeps last only).
    if '"by_token"' in raw_text:
        import re

        blocks = re.findall(r'"by_token"\s*:\s*\{([^}]*)\}', raw_text, re.DOTALL)
        for block in blocks:
            token_keys = re.findall(r'"([A-Za-z0-9_-]+)"\s*:\s*\{', block)
            if len(token_keys) != len(set(token_keys)):
                fail(
                    "claimable-classification raw JSON has duplicate keys in funds_ledger.by_token "
                    f"(tokens={token_keys!r})"
                )

    for inv_id, row in (obs.get("boundary_headroom") or {}).items():
        if isinstance(row, dict) and "worlds_tracked" in row:
            fail(
                f"claimable-classification boundary_headroom.{inv_id} uses deprecated worlds_tracked; "
                "use workflows_tracked"
            )

    sid = obs.get("scenario_id")
    status = obs.get("scenario_id_status")
    if sid == "unknown" and status == "missing-from-result":
        fail(
            "claimable-classification scenario_id is unknown with missing-from-result; "
            "derive from result path or fix result JSON"
        )

    if obs and "coverage_status" not in obs:
        fail("claimable-classification terminal_observations missing coverage_status")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--registry", default="results/test-artifacts/test-artifacts.json")
    ap.add_argument("--run-manifest", default="results/test-artifacts/test-run.json")
    ap.add_argument("--summary", default="results/test-artifacts/test-summary.json")
    ap.add_argument("--claimable", default="results/test-artifacts/claimable-classification.json")
    args = ap.parse_args()

    registry_p = Path(args.registry)
    run_p = Path(args.run_manifest)
    summary_p = Path(args.summary)
    claim_p = Path(args.claimable)

    for p in (registry_p, run_p, summary_p, claim_p):
        if not p.exists():
            fail(f"required file missing: {p}")

    registry = load_json(registry_p)
    run = load_json(run_p)
    summary = load_json(summary_p)
    claim_raw = claim_p.read_text()
    claim = json.loads(claim_raw)

    validate_schema_const(registry, "test-artifacts.v1", "test-artifacts")
    validate_schema_const(run, "test-run.v1", "test-run")
    validate_schema_const(summary, "test-summary.v2", "test-summary")
    claim_schema = claim.get("schema_version")
    if claim_schema not in ("claimable-classification.v1", "claimable-classification.v2"):
        fail(f"claimable-classification schema_version must be v1 or v2, got {claim_schema!r}")
    obs_status = claim.get("observations_status")
    if obs_status and obs_status not in (
        "taxonomy-only",
        "terminal-aggregated",
        "single-scenario",
    ):
        fail(f"claimable-classification observations_status invalid: {obs_status!r}")

    if registry.get("run_id") != run.get("run_id"):
        fail("run_id mismatch between registry and run manifest")

    rm = registry.get("run_manifest") or {}
    if rm.get("path") != str(run_p):
        fail("run_manifest.path does not match expected run manifest path")

    expected_run_sha = sha256_file(run_p)
    if rm.get("sha256") != expected_run_sha:
        fail("run manifest sha256 mismatch")

    artifacts = registry.get("artifacts") or []
    if not isinstance(artifacts, list) or not artifacts:
        fail("registry.artifacts is empty or invalid")

    by_id = {}
    for a in artifacts:
        aid = a.get("id")
        if not aid:
            fail("artifact entry missing id")
        by_id[aid] = a
        p = Path(a.get("path", ""))
        if not p.exists():
            fail(f"artifact file missing for id={aid}: {p}")
        expected_sha = sha256_file(p)
        if a.get("sha256") != expected_sha:
            fail(f"sha256 mismatch for artifact id={aid}")

    # Required chain anchors
    for req in ("test-summary", "test-run", "claimable-classification"):
        if req not in by_id:
            fail(f"required artifact id missing from registry: {req}")

    # Compatibility assertions
    ts = by_id["test-summary"]
    iv = ts.get("input_versions") or {}
    if iv.get("test_run") != "test-run.v1":
        fail("test-summary input_versions.test_run must be test-run.v1")
    if ts.get("schema_version") != summary.get("schema_version"):
        fail("test-summary schema_version mismatch between registry and file")

    shortfall_exposure = summary.get("shortfall_exposure") or {}
    for k in ("shortfall_related_scenarios", "partial_liquidity_enabled_scenarios", "rounding_policy"):
        if k not in shortfall_exposure:
            fail(f"test-summary missing shortfall_exposure.{k}")
    if shortfall_exposure.get("rounding_policy") != "floor-to-asset-decimals.v1":
        fail("test-summary shortfall_exposure.rounding_policy must be floor-to-asset-decimals.v1")

    cc = by_id["claimable-classification"]
    if cc.get("schema_version") != claim.get("schema_version"):
        fail("claimable-classification schema_version mismatch between registry and file")
    sp = claim.get("shortfall_policy") or {}
    for k in ("mode", "allocation", "rounding_policy"):
        if k not in sp:
            fail(f"claimable-classification missing shortfall_policy.{k}")
    if sp.get("rounding_policy") != "floor-to-asset-decimals.v1":
        fail("claimable-classification shortfall_policy.rounding_policy must be floor-to-asset-decimals.v1")

    validate_claimable_v2_integrity(claim, claim_raw)

    print("[artifact-registry] PASS: integrity + compatibility checks succeeded")
    return 0


if __name__ == "__main__":
    sys.exit(main())
