#!/usr/bin/env python3
"""Reproduce a forensic run from its bundle and compare results.

Usage:
    python3 scripts/forensic/reproduce.py <run-dir> [--output-base <dir>]
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

PRF_RUNS_ROOT = Path("~/prf-runs").expanduser()


def load_json(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text())
    except Exception:
        return None


def compute_sha256(data: bytes) -> str:
    import hashlib
    return hashlib.sha256(data).hexdigest()


def reproduce_run(run_dir: str | Path,
                  output_base: str | Path | None = None) -> int:
    run_dir = Path(run_dir).expanduser().resolve()
    if not run_dir.exists():
        print(f"Run directory not found: {run_dir}", file=sys.stderr)
        return 1

    if output_base is None:
        output_base = PRF_RUNS_ROOT
    output_base = Path(output_base).expanduser().resolve()

    # Read original Clojure bundle root (stable, no timestamps)
    orig_clojure_bundle = load_json(run_dir / "clojure-bundle-root.json")
    if not orig_clojure_bundle:
        print("Cannot read original clojure-bundle-root.json", file=sys.stderr)
        print("(Reproduce requires a run that captured the Clojure bundle root)",
              file=sys.stderr)
        return 1

    orig_overview = orig_clojure_bundle.get("overview", {})
    orig_overview_hash = orig_overview.get("overview/hash", "")
    orig_bundle_hash = orig_clojure_bundle.get("bundle/hash", "")
    print(f"Original overview hash: {orig_overview_hash[:20]}...", file=sys.stderr)
    print(f"Original bundle hash:   {orig_bundle_hash[:20]}...", file=sys.stderr)

    # Build reproduce command — same as forensic runner
    run_req = load_json(run_dir / "run-request.json")
    suite_key = None
    if run_req:
        suite_key = (run_req.get("suite/key") or run_req.get("key"))

    label = f"reproduce-{run_dir.name[:30]}"
    repro_id = f"{datetime.now(timezone.utc).strftime('%Y-%m-%dT%H-%M-%SZ')}-{label}"
    repro_dir = output_base / repro_id

    print(f"Reproduce run id: {repro_id}", file=sys.stderr)
    print(f"Reproduce output: {repro_dir}", file=sys.stderr)

    repro_dir.mkdir(parents=True, exist_ok=False)

    # Set up env vars from original source info if available
    orig_src = orig_overview.get("source", {})
    env = os.environ.copy()
    if orig_src.get("code-hash"):
        env["PRF_SOURCE_TREE_HASH"] = orig_src["code-hash"]
        env["PRF_SOURCE_TREE_HASH_ALGORITHM"] = orig_src.get(
            "code-hash-algorithm", "source-tree-hash.v0.shell-sha256sum")
        env["PRF_ORCHESTRATION_RUNNER_ID"] = "forensic-reproduce.py"
        env["PRF_BUNDLE_ID"] = repro_id

    run_expr = ("(require '[resolver-sim.core]) "
                "(->> *command-line-args* (map str) (apply resolver-sim.core/-main))")
    cmd = ["clojure", "-M:with-sew", "-e", run_expr,
           "--", "--invariants"]
    if suite_key:
        cmd.extend(["--suite", str(suite_key)])
    output_path = repro_dir / "clojure-bundle-root.json"
    cmd.extend(["--output-file", str(output_path)])

    print(f"  executing: {' '.join(cmd)}", file=sys.stderr)
    t0 = time.time()
    r = subprocess.run(cmd, capture_output=False, timeout=600, env=env)
    elapsed_ms = int((time.time() - t0) * 1000)
    print(f"  exit code: {r.returncode}  ({elapsed_ms}ms)", file=sys.stderr)

    # Read new Clojure bundle root
    new_bundle = load_json(output_path)
    if not new_bundle:
        print("Reproduce produced no Clojure bundle root", file=sys.stderr)
        return 1

    # Compare overview hashes (Clojure-side — stable, no timestamps)
    new_overview = new_bundle.get("overview", {})
    new_overview_hash = new_overview.get("overview/hash", "")
    repro_summary = {
        "reproduce/schema-version": "forensic-reproduce.v1",
        "reproduce/original-run": str(run_dir),
        "reproduce/reproduce-run": repro_id,
        "reproduce/timestamp": datetime.now(timezone.utc).isoformat(),
        "reproduce/elapsed-ms": elapsed_ms,
        "reproduce/exit-code": r.returncode,
        "reproduce/original-overview-hash": orig_overview_hash,
        "reproduce/new-overview-hash": new_overview_hash,
        "reproduce/overview-hashes-match": orig_overview_hash == new_overview_hash,
        "reproduce/suite-key": suite_key or "registry-default",
    }

    repro_file = repro_dir / "reproduce-report.json"
    repro_file.write_text(json.dumps(repro_summary, indent=2))

    if orig_overview_hash == new_overview_hash:
        print(f"\n✅ REPRODUCED — overview hashes match", file=sys.stderr)
        print(f"   {orig_overview_hash[:20]}...", file=sys.stderr)
        return 0
    else:
        print(f"\n❌ DIVERGED — overview hashes differ", file=sys.stderr)
        print(f"   Original: {orig_overview_hash[:20]}...", file=sys.stderr)
        print(f"   New:      {new_overview_hash[:20]}...", file=sys.stderr)
        return 1


def main():
    import argparse
    parser = argparse.ArgumentParser(
        description="Reproduce a forensic run from its bundle")
    parser.add_argument("run_dir", help="Path to forensic run directory")
    parser.add_argument("--output-base", default=str(PRF_RUNS_ROOT),
                        help=f"Base directory for reproduce output (default: {PRF_RUNS_ROOT})")
    args = parser.parse_args()
    sys.exit(reproduce_run(args.run_dir, args.output_base))


if __name__ == "__main__":
    main()
