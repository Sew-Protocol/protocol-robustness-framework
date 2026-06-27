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

from scripts.forensic.preflight import (run_preflight, parse_edn_or_json,
                                        _get_key, write_sealed_json,
                                        compute_sha256)
from scripts.forensic.verify import verify_run
from scripts.forensic.isolation_checks import run_isolation_checks

SIGN_EXCLUDE_KEYS = frozenset({"bundle/id", "bundle/hash",
                                "bundle/signature", "bundle/signing-key-id"})

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

    # Source byte size (total source code footprint)
    try:
        r = subprocess.run(
            ["du", "-sb", "src", "protocols_src"],
            capture_output=True, text=True, cwd=str(repo_root), timeout=10)
        total = 0
        for line in r.stdout.strip().split("\n"):
            parts = line.split("\t")
            if parts and parts[0].isdigit():
                total += int(parts[0])
        info["source-byte-size"] = total
    except Exception:
        info["source-byte-size"] = 0

    # Code hash: deterministic hash over all source files
    try:
        r = subprocess.run(
            ["bash", "-c",
             "find src protocols_src -type f | sort | xargs sha256sum 2>/dev/null | sha256sum"],
            capture_output=True, text=True, cwd=str(repo_root), timeout=30)
        ch = r.stdout.strip().split()[0] if r.stdout.strip() else "unknown"
        info["code-hash"] = ch
    except Exception:
        info["code-hash"] = "unknown"

    return info


def snapshot_environment() -> dict:
    import platform
    return {
        "os": platform.system(),
        "os_release": platform.release(),
        "python_version": platform.python_version(),
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }


# write_json replaced by write_sealed_json from preflight module


def sign_bundle_hash(bundle_hash: str, key_path: str,
                     key_id: str | None = None) -> tuple[str, str]:
    """Sign a bundle hash with an Ed25519 key using the Clojure signing
    infrastructure. Returns (hex_signature, key_id)."""
    if key_id is None:
        key_id = Path(key_path).name
    try:
        clj_code = (
            f"(require '[resolver-sim.benchmark.signing :as s]) "
            f"(println (s/sign-hash \"{bundle_hash}\" \"{key_path}\" nil))")
        r = subprocess.run(
            ["clojure", "-M:with-sew", "-e", clj_code],
            capture_output=True, text=True, timeout=60)
        if r.returncode != 0:
            raise RuntimeError(f"Clojure signing failed: {r.stderr.strip()}")
        sig = r.stdout.strip()
        if not sig:
            raise RuntimeError("Clojure signing returned empty signature")
        print(f"  signed bundle root with key {key_id}", file=sys.stderr)
        return sig, key_id
    except Exception as e:
        raise RuntimeError(f"Bundle signing failed: {e}") from e


def make_output_immutable(run_dir: Path) -> None:
    """Make output directory read-only after run completes.
    First removes write permission from all files and directories,
    then tries to set the immutable filesystem attribute (best-effort)."""
    try:
        subprocess.run(["chmod", "-R", "a-w", str(run_dir)],
                       check=True, capture_output=True, timeout=30)
        print(f"  output tree set to read-only (chmod -R a-w)", file=sys.stderr)
    except Exception as e:
        print(f"  warning: could not set read-only: {e}", file=sys.stderr)
        return

    # Best-effort: try to set immutable filesystem attribute
    try:
        subprocess.run(["chattr", "-R", "+i", str(run_dir)],
                       capture_output=True, timeout=30)
        print(f"  immutable flag set (chattr +i)", file=sys.stderr)
    except Exception:
        pass  # chattr typically requires root or CAP_LINUX_IMMUTABLE


def run_invoke_scenario_pipeline(run_request_path: str,
                                 run_dir: Path) -> int:
    """Invoke the existing scenario run pipeline.
    Returns exit code. Output passes through to terminal (not captured)."""
    run_request = None
    try:
        p = Path(run_request_path).expanduser()
        text = p.read_text()
        run_request = parse_edn_or_json(text, str(p)) or {}
    except Exception:
        pass

    suite_key = _get_key(run_request, "suite/key", ":suite/key", "key") if run_request else None

    # Build command using -e directly (the :run alias's :main-opts doesn't
    # reliably pass --output-file through the Clojure CLI with -M).
    run_expr = ("(require '[resolver-sim.core]) "
                "(->> *command-line-args* (map str) (apply resolver-sim.core/-main))")
    cmd = ["clojure", "-M:with-sew", "-e", run_expr,
           "--", "--invariants"]
    if suite_key:
        cmd.extend(["--suite", str(suite_key)])
    output_path = run_dir / "clojure-bundle-root.json"
    cmd.extend(["--output-file", str(output_path)])

    print(f"  executing: {' '.join(cmd)}", file=sys.stderr)
    # Run with capture_output=False so output passes through to terminal
    # (Clojure/JVM output goes through paths that require a TTY and cannot be
    #  reliably captured via PIPE. Exit code is the reliable signal.)
    r = subprocess.run(cmd, capture_output=False, timeout=600)
    if output_path.exists():
        print(f"  captured Clojure bundle root ({output_path.stat().st_size} bytes)",
              file=sys.stderr)
    return r.returncode


def _bundle_scenario_files(run_dir: Path, suite_key: str) -> None:
    """Look up scenario file paths for a named suite and copy them into the
    bundle's scenarios/ directory for portability."""
    try:
        clj_code = (
            f"(require '[resolver-sim.scenario.suites :as suites]) "
            f"(println (pr-str (suites/suite-paths (keyword \"{suite_key}\"))))")
        r = subprocess.run(
            ["clojure", "-M:with-sew", "-e", clj_code],
            capture_output=True, text=True, timeout=30)
        if r.returncode != 0:
            print(f"  warning: could not look up suite '{suite_key}': {r.stderr.strip()[:100]}",
                  file=sys.stderr)
            return
        paths_str = r.stdout.strip()
        import ast
        try:
            paths = ast.literal_eval(paths_str)
        except Exception:
            print(f"  warning: could not parse suite paths: {paths_str[:100]}",
                  file=sys.stderr)
            return
        if not paths:
            print(f"  warning: suite '{suite_key}' has no file paths (in-process scenarios)",
                  file=sys.stderr)
            return
        scenario_dir = run_dir / "scenarios"
        scenario_dir.mkdir(exist_ok=True)
        count = 0
        for rel_path in paths:
            src = Path(rel_path)
            if src.exists():
                shutil.copy2(str(src), str(scenario_dir / src.name))
                count += 1
            else:
                print(f"  warning: scenario file not found: {src}", file=sys.stderr)
        print(f"  bundled {count} scenario file(s) to scenarios/", file=sys.stderr)
    except Exception as e:
        print(f"  warning: scenario bundling failed: {e}", file=sys.stderr)


# ── Main run orchestrator ──────────────────────────────────────────────────

def run_forensic(run_request_path: str = "workspaces/forensic-runner/inputs/run-request.edn",
                 label: str | None = None,
                 registry_snapshot_path: str = "workspaces/forensic-runner/inputs/registry-snapshot.edn",
                 evidence_policy_path: str = "workspaces/forensic-runner/policies/evidence-policy.edn",
                 output_base: str | Path | None = None,
                 repo_root: str | Path | None = None,
                 dry_run: bool = False,
                 isolation: str = "shared-filesystem",
                 signing_key_path: str | None = None,
                 signing_key_id: str | None = None) -> int:
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
    print(f"  isolation: {isolation}", file=sys.stderr)
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
            write_sealed_json(tmp_dir / "preflight-report.json", report.to_dict())
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
    write_sealed_json(run_dir / "preflight-report.json", report.to_dict())

    # Step 3: Snapshot source and environment
    print(f"\n--- Snapshots ---", file=sys.stderr)
    source_info = snapshot_source(repo_root, run_dir)
    env_info = snapshot_environment()
    write_sealed_json(run_dir / "source-snapshot.json", source_info)
    write_sealed_json(run_dir / "environment.json", env_info)

    # Step 3b: OS-level isolation checks
    print(f"\n--- Isolation Checks ---", file=sys.stderr)
    isolation_report = run_isolation_checks(isolation_mode=isolation)
    for c in isolation_report["isolation/checks"]:
        status = c.get("status", "?")
        label = c.get("check", "?")
        detail = c.get("detail", "")
        print(f"  [{status:>5}] {label}: {detail}", file=sys.stderr)
    print(f"  grade: {isolation_report['isolation/grade']}", file=sys.stderr)

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
    write_sealed_json(run_dir / "input-manifest.json", input_manifest)

    # Step 6: Execute scenarios
    print(f"\n--- Execution ---", file=sys.stderr)
    t0 = time.time()
    exit_code = run_invoke_scenario_pipeline(
        run_request_path, run_dir)
    elapsed_ms = int((time.time() - t0) * 1000)
    print(f"  exit code: {exit_code}  ({elapsed_ms}ms)", file=sys.stderr)

    # Step 6b: Bundle scenario files and deps.edn for portability
    run_request_data = None
    if run_request_path:
        try:
            run_request_data = parse_edn_or_json(
                Path(run_request_path).expanduser().read_text(), run_request_path)
        except Exception:
            pass
    suite_key = _get_key(run_request_data, "suite/key", ":suite/key", "key") if run_request_data else None
    if suite_key:
        _bundle_scenario_files(run_dir, suite_key)
    # Snapshot deps.edn for dependency tracking
    deps_path = Path("deps.edn")
    if deps_path.exists():
        try:
            shutil.copy2(str(deps_path), str(run_dir / "deps.edn"))
            print(f"  bundled deps.edn", file=sys.stderr)
        except Exception as e:
            print(f"  warning: could not bundle deps.edn: {e}", file=sys.stderr)

    # Step 6c: Write results summary (minimal — detailed results in overview)
    results_summary = {
        "results/schema-version": "results-summary.v1",
        "results/status": "pass" if exit_code == 0 else "fail",
        "results/suite-key": suite_key or "registry-default",
    }
    write_sealed_json(run_dir / "results-summary.json", results_summary)

    # Step 6d: Copy evidence nodes from Clojure pipeline output
    evidence_node_dir = repo_root / "results" / "test-artifacts" / "evidence-nodes"
    if evidence_node_dir.exists():
        dag_dir = run_dir / "evidence-dag"
        count = 0
        for f in evidence_node_dir.iterdir():
            if f.is_file() and f.suffix in (".json", ".edn"):
                shutil.copy2(str(f), str(dag_dir / f.name))
                count += 1
        if count > 0:
            print(f"  copied {count} evidence node(s) to evidence-dag/", file=sys.stderr)

    # Step 7: Write run overview (self-referential SHA-256)
    run_overview = {
        "overview/schema-version": "run-overview.v1",
        "overview/hash": None,   # filled in step below
        "run-id": run_id,
        "run-timestamp": datetime.now(timezone.utc).isoformat(),
        "exit-code": exit_code,
        "elapsed-ms": elapsed_ms,
        "status": "pass" if exit_code == 0 else "fail",
        "source": source_info,
    }
    # Build canonical dict (without hash), serialize, hash, set hash, seal
    overview_canonical = {k: v for k, v in run_overview.items()
                          if k not in ("overview/hash",)}
    can_bytes = json.dumps(overview_canonical, indent=2, default=str,
                           sort_keys=True).encode("utf-8")
    overview_hash = compute_sha256(can_bytes)
    run_overview["overview/hash"] = overview_hash
    write_sealed_json(run_dir / "run-overview.json", run_overview)
    print(f"  overview hash: {overview_hash[:16]}...", file=sys.stderr)

    # Step 8: Write bundle root (self-referential SHA-256 + optional signing)
    bundle_root = {
        "bundle/schema-version": "bundle-root.v1",
        "bundle/id": None,        # filled after hash
        "bundle/hash": None,      # self-referential hash
        "bundle/signature": None, # filled after signing
        "bundle/signing-key-id": None,
        "bundle/timestamp": datetime.now(timezone.utc).isoformat(),
        "run/request-path": str(run_request_path),
        "run/exit-code": exit_code,
        "run/status": "pass" if exit_code == 0 else "fail",
        "overview/hash": overview_hash,
        "preflight": {
            "status": preflight_status,
            "summary": report.summary,
        },
    }
    bundle_root.update(isolation_report)
    bundle_canonical = {k: v for k, v in bundle_root.items()
                        if k not in SIGN_EXCLUDE_KEYS}
    can_bytes = json.dumps(bundle_canonical, indent=2, default=str,
                           sort_keys=True).encode("utf-8")
    bundle_hash = compute_sha256(can_bytes)
    bundle_root["bundle/id"] = bundle_hash
    bundle_root["bundle/hash"] = bundle_hash

    # Optional: sign the bundle hash with Ed25519
    if signing_key_path:
        sig, kid = sign_bundle_hash(bundle_hash, signing_key_path, signing_key_id)
        bundle_root["bundle/signature"] = sig
        bundle_root["bundle/signing-key-id"] = kid

    write_sealed_json(run_dir / "run-bundle-root.json", bundle_root)
    print(f"  bundle root hash: {bundle_hash[:16]}...", file=sys.stderr)

    # Step 9: Write anchor cursor (mock)
    anchor_cursor = {
        "anchor/schema-version": "anchor-cursor.v1",
        "anchor/type": "mock",
        "anchor/target": f"file://{run_dir}",
        "anchor/timestamp": datetime.now(timezone.utc).isoformat(),
        "anchor/note": "Mock anchor — no external anchoring in this phase",
    }
    write_sealed_json(run_dir / "anchors/anchor-cursor.json", anchor_cursor)

    # Step 10: Post-run full verification
    print(f"\n--- Post-run Verification ---", file=sys.stderr)
    try:
        v_report = verify_run(str(run_dir))
        v_summary = v_report.summary
        if v_report.status == "fail":
            print(f"  Verification FAILED: {v_summary['fail']} check(s) failed",
                  file=sys.stderr)
        else:
            print(f"  Verification {v_report.status} "
                  f"({v_summary['pass']} pass, {v_summary['fail']} fail, "
                  f"{v_summary['warning']} warn)",
                  file=sys.stderr)
    except Exception as e:
        print(f"  Verification error: {e}", file=sys.stderr)

    # Step 11: Make output tree immutable
    print(f"\n--- Output Hardening ---", file=sys.stderr)
    make_output_immutable(run_dir)

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
    parser.add_argument("--isolation",
                        default="shared-filesystem",
                        choices=["shared-filesystem", "private-tmpfs"],
                        help="Filesystem isolation level (default: shared-filesystem)")
    parser.add_argument("--sign", dest="signing_key_path",
                        default=None,
                        help="Path to Ed25519 private key for bundle signing")
    parser.add_argument("--signing-key-id",
                        default=None,
                        help="Key identifier (defaults to key filename)")
    parser.add_argument("--dry-run", action="store_true",
                        help="Run preflight only, no execution")
    parser.add_argument("--no-harden", action="store_true",
                        help="Skip post-run output hardening (chmod a-w)")
    args = parser.parse_args()

    exit_code = run_forensic(
        run_request_path=args.run_request,
        label=args.label,
        registry_snapshot_path=args.registry_snapshot,
        evidence_policy_path=args.evidence_policy,
        output_base=args.output_base,
        repo_root=args.repo_root,
        dry_run=args.dry_run,
        isolation=args.isolation,
        signing_key_path=args.signing_key_path,
        signing_key_id=args.signing_key_id,
    )
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
