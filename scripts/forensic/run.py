#!/usr/bin/env python3
"""Forensic run orchestrator.

Runs preflight checks, creates an isolated output directory in ~/prf-runs/,
invokes the scenario execution pipeline, and produces a run bundle.

Usage:
    python3 scripts/forensic/run.py --run-request <path> [--label <label>] [--dry-run]
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

# Ensure project root is on sys.path for sibling module imports
_project_root = Path(__file__).resolve().parent.parent.parent
if str(_project_root) not in sys.path:
    sys.path.insert(0, str(_project_root))

from scripts.forensic.preflight import run_preflight, parse_edn_or_json, _get_key

SCHEMA_VERSION = "forensic-run.v1"
PRF_RUNS_ROOT = Path("~/prf-runs").expanduser()


# ── Helpers ────────────────────────────────────────────────────────────────

def make_run_id(label: str | None = None) -> str:
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")
    if label:
        # Sanitize: keep alphanumeric, hyphens, underscores
        safe = "".join(c if c.isalnum() or c in "-_" else "-" for c in label)
        return f"{ts}-{safe}"
    return ts


def snapshot_source(repo_root: Path, run_dir: Path) -> dict:
    info: dict[str, Any] = {}
    try:
        r = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            capture_output=True, text=True, cwd=str(repo_root), timeout=10)
        if r.returncode == 0:
            info["git_commit"] = r.stdout.strip()
    except Exception:
        info["git_commit"] = "unknown"

    try:
        r = subprocess.run(
            ["git", "status", "--porcelain"],
            capture_output=True, text=True, cwd=str(repo_root), timeout=10)
        info["dirty"] = bool(r.stdout.strip())
    except Exception:
        info["dirty"] = True

    info["repo_root"] = str(repo_root.resolve())
    return info


def snapshot_environment() -> dict:
    import platform
    return {
        "os": platform.system(),
        "os_release": platform.release(),
        "python_version": platform.python_version(),
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, default=str))
    print(f"  wrote {path}", file=sys.stderr)


def run_invoke_scenario_pipeline(run_request_path: str,
                                 output_file: str) -> int:
    """Invoke the existing scenario run pipeline. Returns exit code."""
    # Load run request to determine suite or scenario
    run_request = None
    try:
        p = Path(run_request_path).expanduser()
        text = p.read_text()
        run_request = parse_edn_or_json(text, str(p)) or {}
    except Exception:
        pass

    suite_key = _get_key(run_request, "suite/key", ":suite/key", "key") if run_request else None

    if suite_key:
        cmd = ["clojure", "-M:run:with-sew", "--", "--invariants", "--suite", str(suite_key)]
    else:
        # Default: run all invariants with full classpath
        cmd = ["clojure", "-M:run:with-sew", "--", "--invariants"]

    print(f"  executing: {' '.join(cmd)}", file=sys.stderr)
    r = subprocess.run(cmd, capture_output=False, timeout=600)
    return r.returncode


# ── Main run orchestrator ──────────────────────────────────────────────────

def run_forensic(run_request_path: str = "workspaces/forensic-runner/inputs/run-request.edn",
                 label: str | None = None,
                 registry_snapshot_path: str = "workspaces/forensic-runner/inputs/registry-snapshot.edn",
                 evidence_policy_path: str = "workspaces/forensic-runner/policies/evidence-policy.edn",
                 output_base: str | Path | None = None,
                 repo_root: str | Path | None = None,
                 dry_run: bool = False) -> int:
    if repo_root is None:
        repo_root = Path.cwd()
    repo_root = Path(repo_root).resolve()

    if output_base is None:
        output_base = PRF_RUNS_ROOT
    output_base = Path(output_base).expanduser().resolve()

    # Determine output directory
    run_id = make_run_id(label)
    run_dir = output_base / run_id

    print(f"=== Forensic Run ===", file=sys.stderr)
    print(f"  run-id:    {run_id}", file=sys.stderr)
    print(f"  output:    {run_dir}", file=sys.stderr)
    print(f"  request:   {run_request_path}", file=sys.stderr)
    print(f"  repo:      {repo_root}", file=sys.stderr)
    if dry_run:
        print(f"  DRY RUN — no execution", file=sys.stderr)

    # Step 1: Preflight
    print(f"\n--- Preflight ---", file=sys.stderr)
    report = run_preflight(
        run_request_path=run_request_path,
        registry_snapshot_path=registry_snapshot_path,
        evidence_policy_path=evidence_policy_path,
        output_dir=str(run_dir),
        repo_root=repo_root,
    )
    preflight_status = report.status

    # Print preflight summary
    s = report.summary
    print(f"  Status: {preflight_status} "
          f"({s['pass']} pass, {s['fail']} fail, "
          f"{s['warning']} warn, {s['info']} info)",
          file=sys.stderr)

    if preflight_status == "fail":
        print(f"  Preflight FAILED — aborting", file=sys.stderr)
        # Write preflight report to temp location
        if not dry_run:
            tmp_dir = Path("/tmp") / f"forensic-preflight-{run_id}"
            tmp_dir.mkdir(parents=True, exist_ok=True)
            write_json(tmp_dir / "preflight-report.json", report.to_dict())
            print(f"  Preflight report: {tmp_dir / 'preflight-report.json'}",
                  file=sys.stderr)
        return 1

    if dry_run:
        print(f"\n  DRY RUN — stopping before execution", file=sys.stderr)
        print(f"  Preflight would pass. Output would go to: {run_dir}",
              file=sys.stderr)
        return 0

    # Step 2: Create output directory
    print(f"\n--- Output Setup ---", file=sys.stderr)
    run_dir.mkdir(parents=True, exist_ok=False)
    (run_dir / "evidence-dag").mkdir()
    (run_dir / "claims").mkdir()
    (run_dir / "attestations").mkdir()
    (run_dir / "anchors").mkdir()

    # Write preflight report to output
    write_json(run_dir / "preflight-report.json", report.to_dict())

    # Step 3: Snapshot source and environment
    print(f"\n--- Snapshots ---", file=sys.stderr)
    source_info = snapshot_source(repo_root, run_dir)
    env_info = snapshot_environment()
    write_json(run_dir / "source-snapshot.json", source_info)
    write_json(run_dir / "environment.json", env_info)

    # Step 4: Copy inputs to output
    print(f"\n--- Inputs ---", file=sys.stderr)
    if run_request_path:
        shutil.copy2(run_request_path, run_dir / "run-request.json")
    if registry_snapshot_path:
        rsp = Path(registry_snapshot_path).expanduser()
        if rsp.exists():
            shutil.copy2(rsp, run_dir / "registry-snapshot.json")
    if evidence_policy_path:
        epp = Path(evidence_policy_path).expanduser()
        if epp.exists():
            shutil.copy2(epp, run_dir / "evidence-policy.edn")

    # Step 5: Write input manifest
    input_manifest = {
        "run-request": str(run_request_path),
        "registry-snapshot": str(registry_snapshot_path or ""),
        "evidence-policy": str(evidence_policy_path or ""),
        "source-snapshot": source_info,
        "environment": env_info,
        "run-timestamp": datetime.now(timezone.utc).isoformat(),
    }
    write_json(run_dir / "input-manifest.json", input_manifest)

    # Step 6: Execute scenarios
    print(f"\n--- Execution ---", file=sys.stderr)
    t0 = time.time()
    exit_code = run_invoke_scenario_pipeline(
        run_request_path, str(run_dir / "run-output.json"))
    elapsed_ms = int((time.time() - t0) * 1000)
    print(f"  exit code: {exit_code}  ({elapsed_ms}ms)", file=sys.stderr)

    # Step 7: Write run overview
    run_overview = {
        "overview/schema-version": "run-overview.v1",
        "run-id": run_id,
        "run-timestamp": datetime.now(timezone.utc).isoformat(),
        "exit-code": exit_code,
        "elapsed-ms": elapsed_ms,
        "status": "pass" if exit_code == 0 else "fail",
        "source": source_info,
    }
    write_json(run_dir / "run-overview.json", run_overview)

    # Step 8: Write bundle root
    bundle_root = {
        "bundle/schema-version": "bundle-root.v1",
        "bundle/id": run_id,
        "bundle/timestamp": datetime.now(timezone.utc).isoformat(),
        "run/request-path": str(run_request_path),
        "run/overview": run_overview,
        "run/exit-code": exit_code,
        "run/status": "pass" if exit_code == 0 else "fail",
        "preflight": {
            "status": preflight_status,
            "summary": report.summary,
        },
    }
    write_json(run_dir / "run-bundle-root.json", bundle_root)

    # Step 9: Write anchor cursor (mock)
    anchor_cursor = {
        "anchor/schema-version": "anchor-cursor.v1",
        "anchor/type": "mock",
        "anchor/target": f"file://{run_dir}",
        "anchor/timestamp": datetime.now(timezone.utc).isoformat(),
        "anchor/note": "Mock anchor — no external anchoring in this phase",
    }
    write_json(run_dir / "anchors/anchor-cursor.json", anchor_cursor)

    print(f"\n=== Run Complete ===", file=sys.stderr)
    print(f"  Output: {run_dir}", file=sys.stderr)
    print(f"  Status: {'PASS' if exit_code == 0 else 'FAIL'}", file=sys.stderr)
    return exit_code


# ── CLI entry point ────────────────────────────────────────────────────────

def main():
    import argparse
    parser = argparse.ArgumentParser(
        description="Forensic run orchestrator")
    parser.add_argument("--run-request",
                        default="workspaces/forensic-runner/inputs/run-request.edn",
                        help="Path to run request file (default: workspaces/forensic-runner/inputs/run-request.edn)")
    parser.add_argument("--label", help="Human-readable label for the run")
    parser.add_argument("--registry-snapshot",
                        default="workspaces/forensic-runner/inputs/registry-snapshot.edn",
                        help="Path to registry snapshot file (default: workspaces/forensic-runner/inputs/registry-snapshot.edn)")
    parser.add_argument("--evidence-policy",
                        default="workspaces/forensic-runner/policies/evidence-policy.edn",
                        help="Path to evidence policy file (default: workspaces/forensic-runner/policies/evidence-policy.edn)")
    parser.add_argument("--output-base",
                        default=str(PRF_RUNS_ROOT),
                        help="Base directory for run output (default: ~/prf-runs)")
    parser.add_argument("--repo-root", default=str(Path.cwd()),
                        help="Project repo root (default: cwd)")
    parser.add_argument("--dry-run", action="store_true",
                        help="Run preflight only, no execution")
    args = parser.parse_args()

    exit_code = run_forensic(
        run_request_path=args.run_request,
        label=args.label,
        registry_snapshot_path=args.registry_snapshot,
        evidence_policy_path=args.evidence_policy,
        output_base=args.output_base,
        repo_root=args.repo_root,
        dry_run=args.dry_run,
    )
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
