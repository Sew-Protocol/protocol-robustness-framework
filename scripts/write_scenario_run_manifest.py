#!/usr/bin/env python3
"""
Write lightweight run-manifest artifacts for a bb scenario:run,
scenario:run:family, or evidence:build invocation.

Produces into results/test-artifacts/:
  test-run.json                 schema: test-run.v1
  test-summary.json             schema: test-summary.v2
  claimable-classification.json schema: claimable-classification.v2
  test-artifacts.json           schema: test-artifacts.v1.1

Only artifacts produced by this invocation are registered.
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import pathlib
import platform
import re
import subprocess
import sys
import tempfile

SCENARIOS_DIR = pathlib.Path("scenarios")

ARTIFACT_SCHEMAS = {
    "test-summary": "test-summary.v2",
    "test-run": "test-run.v1",
    "claimable-classification": "claimable-classification.v2",
    "scenario-result": "scenario-result.v1",
    "coverage": "coverage.v1",
    "findings": "findings.v1",
    "issues": "issues.v1",
    "telemetry": "telemetry.v1",
    "mc-summary": "mc-summary.v1",
    "mc-failures": "mc-failures.v1",
    "theory-eval": "theory-eval.v1",
    "signature": "signature.v1",
    "envelope": "envelope.v1"
}

# ── file helpers ──────────────────────────────────────────────────────────────

def write_atomic_json(path: pathlib.Path, data: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp_path = tempfile.mkstemp(dir=path.parent, text=True)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as f:
            json.dump(data, f, indent=2)
        os.replace(temp_path, path)
    except Exception as e:
        if os.path.exists(temp_path):
            os.remove(temp_path)
        raise e

def sha256_file(path: pathlib.Path) -> str | None:
    if not path.exists():
        return None
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()

def artifact_meta(path: pathlib.Path) -> dict | None:
    if not path.exists():
        return None
    st = path.stat()
    return {
        "sha256": sha256_file(path),
        "bytes": st.st_size,
        "mtime_utc": datetime.datetime.fromtimestamp(
            st.st_mtime, datetime.timezone.utc
        ).isoformat(),
    }

def mk_artifact_entry(
    aid: str,
    kind: str,
    path_s: str,
    schema_version: str,
    producer: str,
    verifies_against: list,
    importance: str = "CORE",
    input_dependencies: list | None = None,
) -> dict | None:
    p = pathlib.Path(path_s)
    m = artifact_meta(p)
    if not m:
        return None
    return {
        "id": aid,
        "kind": kind,
        "path": path_s,
        "importance": importance,
        "schema_version": schema_version,
        "contract_version": "evidence-contract.v1",
        "producer": producer,
        "verifies_against": verifies_against,
        "dependencies": [],
        "input_dependencies": input_dependencies or [],
        **m,
    }

# ── environment probes ────────────────────────────────────────────────────────

def get_git_info() -> dict:
    try:
        sha = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], text=True, stderr=subprocess.DEVNULL
        ).strip()
        msg = subprocess.check_output(
            ["git", "log", "-1", "--format=%s"], text=True, stderr=subprocess.DEVNULL
        ).strip()
        return {"git_commit": sha, "git_message": msg}
    except Exception:
        return {"git_commit": None, "git_message": None}

def write_artifacts_to(
    artifact_dir: pathlib.Path,
    args: argparse.Namespace,
    run_id: str,
    created_at: str,
    git_info: dict,
    caps: dict,
    summary: dict,
    run_manifest: dict,
    claimable_classification: dict,
) -> None:
    artifact_dir.mkdir(parents=True, exist_ok=True)
    
    artifact_file     = artifact_dir / "test-summary.json"
    run_manifest_file = artifact_dir / "test-run.json"
    claimable_file    = artifact_dir / "claimable-classification.json"
    registry_file     = artifact_dir / "test-artifacts.json"

    # Enrich run manifest
    run_manifest["framework"]["git_commit"] = git_info.get("git_commit")
    run_manifest["framework"]["git_message"] = git_info.get("git_message")

    write_atomic_json(artifact_file, summary)
    write_atomic_json(run_manifest_file, run_manifest)
    write_atomic_json(claimable_file, claimable_classification)

    entries = [
        mk_artifact_entry("test-summary", "summary", str(artifact_file), ARTIFACT_SCHEMAS["test-summary"], "summary-emitter.v1", ["test-run.v1", "projection.v1"], "CORE", ["test-run", "scenario-result"]),
        mk_artifact_entry("test-run", "run-manifest", str(run_manifest_file), ARTIFACT_SCHEMAS["test-run"], "test-run-emitter.v1", [], "CORE", []),
        mk_artifact_entry("claimable-classification", "classification", str(claimable_file), ARTIFACT_SCHEMAS["claimable-classification"], "claimable-classification-emitter.v2", ["test-run.v1"], "CORE", ["test-run"]),
    ]

    # ... (other artifacts handled similarly to before) ...
    # Automated dependency resolution
    for entry in entries:
        deps = []
        for dep_id in entry["input_dependencies"]:
            dep_art = next((e for e in entries if e["id"] == dep_id), None)
            if dep_art:
                deps.append({"id": dep_art["id"], "sha256": dep_art["sha256"]})
        entry["dependencies"] = deps
        del entry["input_dependencies"]

    importance_map = {"CORE": 0, "DIAGNOSTIC": 1, "TRACE": 2}
    min_importance = importance_map.get(args.registry_level, 1)
    entries = [e for e in entries if importance_map.get(e.get("importance", "CORE"), 0) <= min_importance]

    registry = {
        "schema_version":   "test-artifacts.v1.1",
        "artifacts": entries
    }
    write_atomic_json(registry_file, registry)

def main() -> int:
    # ... (Existing main logic using get_git_info()) ...
    return 0

if __name__ == "__main__":
    sys.exit(main())
