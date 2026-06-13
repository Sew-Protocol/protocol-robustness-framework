#!/usr/bin/env python3
"""
Write lightweight run-manifest artifacts for a bb scenario:run,
scenario:run:family, or evidence:build invocation.

Reads canonical evidence chain configuration from config/evidence.json.

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
import subprocess
import sys
import tempfile

from evidence_config import EvidenceConfig

SCENARIOS_DIR = pathlib.Path("scenarios")


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
    cfg: EvidenceConfig,
    aid: str,
    path_s: str | None,
) -> dict | None:
    a = cfg.artifact(aid)
    if not a or not path_s:
        return None
    p = pathlib.Path(path_s)
    m = artifact_meta(p)
    if not m:
        return None
    return {
        "id": aid,
        "kind": a["kind"],
        "path": str(p),
        "importance": a["importance"],
        "schema_version": cfg.schema(a["schema_key"]),
        "contract_version": cfg.contract_version,
        "producer": cfg.producer(a["producer_key"]),
        "verifies_against": list(a.get("verifies_against", [])),
        "dependencies": [],
        "input_dependencies": list(a.get("input_dependencies", [])),
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

def make_scenario_slug(scenario_path: str | None, suite: str) -> str:
    if not scenario_path or scenario_path == "unknown":
        return suite
    return pathlib.Path(scenario_path).stem

def write_artifacts_to(
    artifact_dir: pathlib.Path,
    cfg: EvidenceConfig,
    args: argparse.Namespace,
    run_id: str,
    created_at: str,
    git_info: dict,
    summary: dict,
    run_manifest: dict,
    claimable_classification: dict,
) -> None:
    artifact_dir.mkdir(parents=True, exist_ok=True)

    artifact_file     = artifact_dir / cfg.artifact("test-summary")["file"]
    run_manifest_file = artifact_dir / cfg.artifact("test-run")["file"]
    claimable_file    = artifact_dir / cfg.artifact("claimable-classification")["file"]
    registry_file     = artifact_dir / "test-artifacts.json"  # registry is self-referencing
    envelope_file     = artifact_dir / cfg.artifact("envelope")["file"]
    signature_file    = artifact_dir / cfg.artifact("signature")["file"]

    # Overwrite protection: If the bundle is already signed, abort.
    # This enforces that once an evidence chain is final, it cannot be changed.
    if signature_file.exists() or envelope_file.exists():
        print(f"Error: Target directory {artifact_dir} contains a signed envelope.")
        print("Evidence chain is FINAL and cannot be modified.")
        sys.exit(1)

    # Enrich run manifest
    run_manifest["framework"]["git_commit"] = git_info.get("git_commit")
    run_manifest["framework"]["git_message"] = git_info.get("git_message")

    write_atomic_json(artifact_file, summary)
    write_atomic_json(run_manifest_file, run_manifest)
    write_atomic_json(claimable_file, claimable_classification)

    # Register all possible artifacts for this run
    all_potential_entries = []
    for aid in cfg.all_artifact_ids:
        if aid == "scenario-result":
            continue  # handled below with user-supplied path
        try:
            p = cfg.artifact_path(aid)
        except KeyError:
            continue
        e = mk_artifact_entry(cfg, aid, p)
        if e:
            all_potential_entries.append(e)

    if args.output_file:
        e = mk_artifact_entry(cfg, "scenario-result", args.output_file)
        if e:
            all_potential_entries.append(e)

    for sid in ("signature", "envelope"):
        f = cfg.artifact(sid)["file"]
        if (artifact_dir / f).exists():
            e = mk_artifact_entry(cfg, sid, str(artifact_dir / f))
            if e:
                all_potential_entries.append(e)

    # Auto-register event evidence
    event_cfg = cfg._data.get("event_evidence", {})
    event_evidence_dir = artifact_dir / (event_cfg.get("dir") or "event-evidence")
    if event_evidence_dir.exists():
        for p in event_evidence_dir.glob("*.json"):
            m = artifact_meta(p)
            if not m:
                continue
            e = {
                "id": f"event-evidence-{p.stem}",
                "kind": event_cfg["kind"],
                "path": str(p),
                "importance": event_cfg["importance"],
                "schema_version": cfg.schema(event_cfg["schema_key"]),
                "contract_version": cfg.contract_version,
                "producer": cfg.producer(event_cfg["producer_key"]),
                "verifies_against": list(event_cfg.get("verifies_against", [])),
                "dependencies": [],
                "input_dependencies": list(event_cfg.get("input_dependencies", [])),
                "evidence_reason": p.name.split("-")[0],
                **m,
            }
            all_potential_entries.append(e)

    # Filtering phase
    imp_map = cfg.importance_map
    min_importance = imp_map.get(args.registry_level, 1)

    root_entries = [
        e for e in all_potential_entries if e
        and imp_map.get(e.get("importance", "CORE"), 1) <= min_importance
    ]

    registered_entries = {e["id"]: e for e in root_entries}
    to_resolve = list(root_entries)

    while to_resolve:
        curr = to_resolve.pop()
        for dep_id in curr.get("input_dependencies", []):
            if dep_id not in registered_entries:
                dep_art = next((e for e in all_potential_entries if e and e["id"] == dep_id), None)
                if dep_art:
                    registered_entries[dep_id] = dep_art
                    to_resolve.append(dep_art)
                else:
                    print(f"Warning: Missing required dependency {dep_id} for {curr['id']}")

    final_entries = list(registered_entries.values())

    for entry in final_entries:
        entry["dependencies"] = []
        for dep_id in entry.get("input_dependencies", []):
            dep_art = registered_entries.get(dep_id)
            if dep_art:
                entry["dependencies"].append({"id": dep_art["id"], "sha256": dep_art["sha256"]})
        if "input_dependencies" in entry:
            del entry["input_dependencies"]

    registry = {
        "schema_version":   cfg.schema("test-artifacts"),
        "contract_version": cfg.contract_version,
        "run_id":           run_id,
        "generated_at":     created_at,
        "generator":        {"name": "artifact-registry-emitter", "version": "v1.1"},
        "root_dir":         str(artifact_dir),
        "artifacts":        final_entries,
    }
    write_atomic_json(registry_file, registry)


def main() -> int:
    cfg = EvidenceConfig()
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenario", default="unknown")
    ap.add_argument("--suite", default="unknown")
    ap.add_argument("--status", default="unknown")
    ap.add_argument("--duration-ms", type=int, default=0)
    ap.add_argument("--output-file")
    ap.add_argument("--artifact-dir", default=cfg.artifact_dir)
    ap.add_argument(
        "--registry-level", default="DIAGNOSTIC",
        choices=list(cfg.importance_map.keys()),
    )
    ap.add_argument("--risk-digest-file")
    ap.add_argument("--long-horizon-meta-file")
    ap.add_argument("--target-log-csv")
    args = ap.parse_args()

    now        = datetime.datetime.now(datetime.timezone.utc)
    run_id     = now.strftime("%Y%m%d-%H%M%S")
    created_at = now.isoformat()
    git_info   = get_git_info()

    summary = {
        "schema_version": cfg.schema("test-summary"),
        "run_id": run_id,
        "overall_status": args.status,
        "risk_digest": {},
    }
    run_manifest = {
        "schema_version": cfg.schema("test-run"),
        "run_id": run_id,
        "framework": dict(cfg.framework),
    }
    claimable = {
        "schema_version": cfg.schema("claimable-classification"),
        "run_id": run_id,
    }

    per_run_dir = pathlib.Path(cfg._data.get("runs_root", "results/runs")) / f"{make_scenario_slug(args.scenario, args.suite)}-{run_id}"
    write_artifacts_to(per_run_dir, cfg, args, run_id, created_at, git_info, summary, run_manifest, claimable)

    print(f"[artifact-registry] Emitted v1.1 to {per_run_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
