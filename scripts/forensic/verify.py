#!/usr/bin/env python3
"""Forensic run verification.

Validates a forensic run output directory: checks bundle root integrity,
verifies all referenced files exist, and reports on evidence DAG consistency.

Usage:
    python3 scripts/forensic/verify.py <run-dir>
    python3 scripts/forensic/verify.py ~/prf-runs/2026-06-26T17-30Z-sew-yield-suite
"""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

# Bundle root keys excluded from self-referential hash computation
_SIGN_EXCLUDE_KEYS = {"bundle/id", "bundle/hash",
                       "bundle/signature", "bundle/signing-key-id"}

# Ensure project root is on sys.path for sibling module imports
_project_root = Path(__file__).resolve().parent.parent.parent
if str(_project_root) not in sys.path:
    sys.path.insert(0, str(_project_root))


SCHEMA_VERSION = "forensic-verify.v1"


# ── Verification model ─────────────────────────────────────────────────────

@dataclass
class VerifyCheck:
    key: str
    status: str   # "pass" | "fail" | "skip"
    message: str
    severity: str  # "required" | "warning" | "info"

    def to_dict(self) -> dict:
        return {"check/key": self.key,
                "check/status": self.status,
                "check/message": self.message,
                "check/severity": self.severity}


@dataclass
class VerifyReport:
    schema_version: str = SCHEMA_VERSION
    run_dir: str = ""
    status: str = "pass"
    checks: list[dict] = field(default_factory=list)
    summary: dict = field(default_factory=dict)

    def to_dict(self) -> dict:
        return {"verify/schema-version": self.schema_version,
                "verify/run-dir": self.run_dir,
                "verify/status": self.status,
                "verify/checks": self.checks,
                "verify/summary": self.summary}


# ── Required files ─────────────────────────────────────────────────────────

REQUIRED_FILES = [
    "preflight-report.json",
    "source-snapshot.json",
    "environment.json",
    "input-manifest.json",
    "run-overview.json",
    "run-bundle-root.json",
]

OPTIONAL_FILES = [
    "run-request.json",
    "registry-snapshot.json",
    "evidence-policy.edn",
    "run-output.log",
    "run-output.json",
    "results-summary.json",
    "deps.edn",
]

OPTIONAL_DIRS = [
    "scenarios",
]

REQUIRED_DIRS = [
    "evidence-dag",
    "claims",
    "attestations",
    "anchors",
]


# ── Checks ─────────────────────────────────────────────────────────────────

def check_exists(run_dir: Path, rel_path: str,
                 severity: str = "required") -> VerifyCheck:
    full = run_dir / rel_path
    key = f"file-exists-{rel_path.replace('/', '-')}"
    if full.exists():
        return VerifyCheck(key=key, status="pass",
                           message=f"{rel_path} exists", severity=severity)
    return VerifyCheck(key=key, status="fail",
                       message=f"{rel_path} not found at {full}",
                       severity=severity)


def check_dir_exists(run_dir: Path, rel_path: str) -> VerifyCheck:
    full = run_dir / rel_path
    key = f"dir-exists-{rel_path.replace('/', '-')}"
    if full.exists() and full.is_dir():
        return VerifyCheck(key=key, status="pass",
                           message=f"Directory {rel_path}/ exists",
                           severity="required")
    return VerifyCheck(key=key, status="fail",
                       message=f"Directory {rel_path}/ not found at {full}",
                       severity="required")


def _load_json(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text())
    except Exception:
        return None


def _canonical_json_bytes(data: dict, exclude_keys: list[str]) -> bytes:
    return json.dumps(
        {k: v for k, v in data.items() if k not in exclude_keys},
        indent=2, default=str, sort_keys=True).encode("utf-8")


def check_bundle_root_hash(run_dir: Path) -> VerifyCheck:
    """Recompute bundle root self-referential hash from canonical fields."""
    path = run_dir / "run-bundle-root.json"
    data = _load_json(path)
    if not data:
        return VerifyCheck(key="bundle-root-hash", status="skip",
                           message="Cannot verify hash: bundle root not parseable",
                           severity="warning")
    recorded = data.get("bundle/hash") or data.get("bundle/id")
    if not recorded:
        return VerifyCheck(key="bundle-root-hash", status="fail",
                           message="Bundle root has no bundle/hash or bundle/id",
                           severity="warning")
    expected = hashlib.sha256(
        _canonical_json_bytes(data, list(_SIGN_EXCLUDE_KEYS))).hexdigest()
    if recorded == expected:
        return VerifyCheck(key="bundle-root-hash", status="pass",
                           message=f"Bundle root hash matches ({recorded[:16]}...)",
                           severity="warning")
    return VerifyCheck(key="bundle-root-hash", status="fail",
                       message=f"Bundle root hash MISMATCH: recorded={recorded[:16]}... expected={expected[:16]}...",
                       severity="warning")


def check_bundle_signature(run_dir: Path,
                           public_key_path: str | None = None) -> VerifyCheck:
    """Verify Ed25519 signature on bundle root hash.
    Requires --public-key to verify; without it the check is skipped."""
    path = run_dir / "run-bundle-root.json"
    data = _load_json(path)
    if not data:
        return VerifyCheck(key="bundle-signature", status="skip",
                           message="Cannot verify signature: bundle root not parseable",
                           severity="warning")

    sig = data.get("bundle/signature")
    kid = data.get("bundle/signing-key-id")
    bundle_hash = data.get("bundle/hash")

    if not sig or not bundle_hash:
        return VerifyCheck(key="bundle-signature", status="info",
                           message="Bundle is not signed (no signature field)",
                           severity="info")

    if not public_key_path:
        return VerifyCheck(key="bundle-signature", status="info",
                           message=f"Bundle signed with key '{kid}' but no --public-key provided to verify",
                           severity="info")

    pk_path = Path(public_key_path).expanduser()
    if not pk_path.exists():
        return VerifyCheck(key="bundle-signature", status="fail",
                           message=f"Public key not found at {pk_path}",
                           severity="warning")

    try:
        clj_code = (
            f"(require '[resolver-sim.benchmark.signing :as s]) "
            f"(println (s/verify-signature \"{bundle_hash}\" \"{sig}\" "
            f"\"{pk_path}\"))")
        r = subprocess.run(
            ["clojure", "-M:with-sew", "-e", clj_code],
            capture_output=True, text=True, timeout=60)
        valid = r.stdout.strip() == "true"
        if valid:
            key_label = kid or public_key_path
            return VerifyCheck(key="bundle-signature", status="pass",
                               message=f"Ed25519 signature valid (key: {key_label})",
                               severity="warning")
        return VerifyCheck(key="bundle-signature", status="fail",
                           message=f"Ed25519 signature INVALID (key: {kid or public_key_path})",
                           severity="warning")
    except Exception as e:
        return VerifyCheck(key="bundle-signature", status="error",
                           message=f"Signature verification error: {e}",
                           severity="warning")


def check_results_summary(run_dir: Path) -> VerifyCheck:
    """Validate results-summary.json if present."""
    path = run_dir / "results-summary.json"
    if not path.exists():
        return VerifyCheck(key="results-summary", status="info",
                           message="No results-summary.json (not produced by older runs)",
                           severity="info")
    data = _load_json(path)
    if not data:
        return VerifyCheck(key="results-summary", status="fail",
                           message="results-summary.json is not valid JSON",
                           severity="warning")
    fail_count = data.get("results/fail-count", 0)
    per_scenario = data.get("results/per-scenario", [])
    summary = f"{len(per_scenario)} scenarios, {fail_count} failed"
    if fail_count > 0:
        return VerifyCheck(key="results-summary", status="fail",
                           message=f"Results: {summary}",
                           severity="warning")
    return VerifyCheck(key="results-summary", status="pass",
                       message=f"Results: {summary}",
                       severity="info")


def check_overview_hash(run_dir: Path) -> VerifyCheck:
    """Recompute overview self-referential hash from canonical fields."""
    path = run_dir / "run-overview.json"
    data = _load_json(path)
    if not data:
        return VerifyCheck(key="overview-hash", status="skip",
                           message="Cannot verify hash: overview not parseable",
                           severity="warning")
    recorded = data.get("overview/hash")
    if not recorded:
        return VerifyCheck(key="overview-hash", status="fail",
                           message="Run overview has no overview/hash field",
                           severity="warning")
    expected = hashlib.sha256(
        _canonical_json_bytes(data, ["overview/hash"])).hexdigest()
    if recorded == expected:
        return VerifyCheck(key="overview-hash", status="pass",
                           message=f"Overview hash matches ({recorded[:16]}...)",
                           severity="warning")
    return VerifyCheck(key="overview-hash", status="fail",
                       message=f"Overview hash MISMATCH: recorded={recorded[:16]}... expected={expected[:16]}...",
                       severity="warning")


def check_bundle_root_valid(run_dir: Path) -> VerifyCheck:
    path = run_dir / "run-bundle-root.json"
    if not path.exists():
        return VerifyCheck(key="bundle-root-valid", status="fail",
                           message="run-bundle-root.json not found",
                           severity="required")
    try:
        data = json.loads(path.read_text())
        required_keys = ["bundle/schema-version", "bundle/id",
                         "run/overview", "preflight"]
        missing = [k for k in required_keys if k not in data]
        if missing:
            return VerifyCheck(key="bundle-root-valid", status="fail",
                               message=f"Bundle root missing keys: {missing}",
                               severity="required")
        if data.get("bundle/schema-version") != "bundle-root.v1":
            return VerifyCheck(key="bundle-root-valid", status="fail",
                               message="Bundle root has wrong schema version",
                               severity="required")
        return VerifyCheck(key="bundle-root-valid", status="pass",
                           message=f"Bundle root valid (id: {data.get('bundle/id', '?')})",
                           severity="required")
    except Exception as e:
        return VerifyCheck(key="bundle-root-valid", status="fail",
                           message=f"Bundle root parse error: {e}",
                           severity="required")


def check_overview_valid(run_dir: Path) -> VerifyCheck:
    path = run_dir / "run-overview.json"
    if not path.exists():
        return VerifyCheck(key="overview-valid", status="fail",
                           message="run-overview.json not found",
                           severity="required")
    try:
        data = json.loads(path.read_text())
        required = ["run-id", "status", "exit-code"]
        missing = [k for k in required if k not in data]
        if missing:
            return VerifyCheck(key="overview-valid", status="fail",
                               message=f"Run overview missing keys: {missing}",
                               severity="required")
        return VerifyCheck(key="overview-valid", status="pass",
                           message=f"Run overview valid (id: {data.get('run-id', '?')})",
                           severity="required")
    except Exception as e:
        return VerifyCheck(key="overview-valid", status="fail",
                           message=f"Run overview parse error: {e}",
                           severity="required")


def check_preflight_valid(run_dir: Path) -> VerifyCheck:
    path = run_dir / "preflight-report.json"
    if not path.exists():
        return VerifyCheck(key="preflight-valid", status="fail",
                           message="preflight-report.json not found",
                           severity="required")
    try:
        data = json.loads(path.read_text())
        status = data.get("preflight/status", "unknown")
        if status in ("pass", "pass-with-warnings"):
            return VerifyCheck(key="preflight-valid", status="pass",
                               message=f"Preflight status: {status}",
                               severity="required")
        return VerifyCheck(key="preflight-valid", status="fail",
                           message=f"Preflight status: {status}",
                           severity="required")
    except Exception as e:
        return VerifyCheck(key="preflight-valid", status="fail",
                           message=f"Preflight parse error: {e}",
                           severity="required")


def check_evidence_dag(run_dir: Path) -> VerifyCheck:
    dag_dir = run_dir / "evidence-dag"
    if not dag_dir.exists():
        return VerifyCheck(key="evidence-dag-present", status="fail",
                           message="evidence-dag/ directory not found",
                           severity="required")
    files = list(dag_dir.iterdir())
    # Evidence DAG may be empty for minimal runs — that's informational
    return VerifyCheck(key="evidence-dag-present", status="info",
                       message=f"evidence-dag/ contains {len(files)} file(s)",
                       severity="info")


def check_no_extra_dirs(run_dir: Path) -> VerifyCheck:
    """Warn about unexpected top-level entries in the run directory."""
    known = set(REQUIRED_FILES + OPTIONAL_FILES + [d + "/" for d in REQUIRED_DIRS])
    extra = []
    for entry in run_dir.iterdir():
        name = entry.name + "/" if entry.is_dir() else entry.name
        if name not in known:
            extra.append(entry.name)
    if extra:
        return VerifyCheck(key="no-extra-entries", status="info",
                           message=f"Extra entries in run dir: {extra}",
                           severity="info")
    return VerifyCheck(key="no-extra-entries", status="pass",
                       message="No unexpected entries in run directory",
                       severity="info")


# ── Main verification ─────────────────────────────────────────────────────

def verify_run(run_dir: str | Path,
               public_key_path: str | None = None) -> VerifyReport:
    run_dir = Path(run_dir).expanduser().resolve()

    if not run_dir.exists():
        return VerifyReport(
            schema_version=SCHEMA_VERSION,
            run_dir=str(run_dir),
            status="fail",
            checks=[VerifyCheck(key="run-dir-exists", status="fail",
                                message=f"Run directory not found: {run_dir}",
                                severity="required").to_dict()],
            summary={"total": 1, "pass": 0, "fail": 1, "warning": 0, "info": 0})

    results: list[VerifyCheck] = []

    # Check required files
    for f in REQUIRED_FILES:
        results.append(check_exists(run_dir, f, severity="required"))

    # Check optional files
    for f in OPTIONAL_FILES:
        results.append(check_exists(run_dir, f, severity="info"))

    # Check required directories
    for d in REQUIRED_DIRS:
        results.append(check_dir_exists(run_dir, d))

    # Check optional directories
    for d in OPTIONAL_DIRS:
        results.append(check_exists(run_dir, d, severity="info"))

    # Structural checks
    results.append(check_bundle_root_valid(run_dir))
    results.append(check_overview_valid(run_dir))
    results.append(check_preflight_valid(run_dir))
    results.append(check_evidence_dag(run_dir))
    results.append(check_no_extra_dirs(run_dir))

    # Sealing integrity checks (warning severity)
    results.append(check_bundle_root_hash(run_dir))
    results.append(check_overview_hash(run_dir))
    results.append(check_bundle_signature(run_dir, public_key_path))
    results.append(check_results_summary(run_dir))

    # Aggregate
    check_dicts = [r.to_dict() for r in results]
    required_fails = sum(1 for r in results
                         if r.severity == "required" and r.status == "fail")
    warnings = sum(1 for r in results if r.status == "warning")
    passes = sum(1 for r in results if r.status == "pass")
    infos = sum(1 for r in results if r.severity == "info")

    if required_fails > 0:
        status = "fail"
    elif warnings > 0:
        status = "pass-with-warnings"
    else:
        status = "pass"

    return VerifyReport(
        schema_version=SCHEMA_VERSION,
        run_dir=str(run_dir),
        status=status,
        checks=check_dicts,
        summary={
            "total": len(results),
            "pass": passes,
            "fail": required_fails,
            "warning": warnings,
            "info": infos,
        })


# ── CLI entry point ────────────────────────────────────────────────────────

def main():
    import argparse
    parser = argparse.ArgumentParser(
        description="Forensic run verification")
    parser.add_argument("run_dir", help="Path to forensic run directory")
    parser.add_argument("--public-key",
                        help="Path to Ed25519 public key for signature verification")
    parser.add_argument("--output", "-o",
                        help="Write verification report to file")
    args = parser.parse_args()

    report = verify_run(args.run_dir, public_key_path=args.public_key)

    output = json.dumps(report.to_dict(), indent=2)
    if args.output:
        Path(args.output).expanduser().write_text(output)
        print(f"Verification report written to {args.output}", file=sys.stderr)
    else:
        print(output)

    s = report.summary
    print(f"\nVerify status: {report.status} "
          f"({s['pass']} pass, {s['fail']} fail, "
          f"{s['warning']} warn, {s['info']} info)",
          file=sys.stderr)
    sys.exit(0 if report.status in ("pass", "pass-with-warnings") else 1)


if __name__ == "__main__":
    main()
