#!/usr/bin/env python3
"""
Extract scenario-scoped artifacts from a replay output JSON into a
browsable, per-run artifact bundle.

Usage:
  python3 scripts/extract_scenario_artifacts.py \
    --replay /tmp/s19-result.json \
    --run-dir results/runs/s19-.../

Outputs:
  summaries/trace-summary.json
  summaries/metrics.json
  summaries/claimable-classification.json
  summaries/mechanism-summary.json
  state/world-final.json
  raw/replay-output.json

All artifacts include a 'derived_from' reference to raw/replay-output.json.
"""

from __future__ import annotations

SCHEMA_MAP = {
    "mechanism-summary.v1": {
        "description": "High-level summary of simulation mechanism outcomes (escrow, disputes, slashing).",
        "fields": {
            "scenario_id": "Unique identifier of the scenario.",
            "outcome": "Final simulation outcome (pass/fail).",
            "escrow": "Aggregated stats on escrow lifecycles.",
            "dispute": "Aggregated stats on dispute resolutions.",
            "slashing": "Aggregated stats on slashing actions.",
            "claimable": "Summary of claimable fund classifications.",
            "temporal": "Temporal statistics (steps, consistency)."
        }
    },
    "scenario-metrics.v1": {
        "description": "Raw numeric metrics collected during simulation.",
        "fields": {
            "scenario_id": "Unique identifier of the scenario.",
            "metrics": "Detailed numeric metrics."
        }
    }
}

ACTION_DESCRIPTIONS = {
    "create_escrow": "Created a new escrow",
    "release_escrow": "Released funds from escrow",
    "raise_dispute": "Raised a dispute",
    "execute_resolution": "Executed a dispute resolution",
    "escalate_dispute": "Escalated a dispute",
    "propose_fraud_slash": "Proposed a fraud slash",
    "execute_fraud_slash": "Executed a fraud slash",
}

import argparse
import hashlib
import json
import os
import pathlib
import sys


# ── Helpers ──────────────────────────────────────────────────────────────────


def sha256_file(path: pathlib.Path) -> str | None:
    if not path.exists():
        return None
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()


def write_json(path: pathlib.Path, data: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, default=str)


def derived_from(replay_path: pathlib.Path) -> dict:
    return {"derived_from": {"path": str(replay_path), "sha256": sha256_file(replay_path)}}


def safe_int(v, default=0):
    if v is None:
        return default
    if isinstance(v, int):
        return v
    try:
        return int(v)
    except (ValueError, TypeError):
        return default


# ── Extractors ───────────────────────────────────────────────────────────────


def extract_trace_summary(replay: dict, replay_path: pathlib.Path) -> dict:
    trace = replay.get("trace", [])
    steps = []
    for i, ev in enumerate(trace):
        seq = safe_int(ev.get("seq"), i)
        steps.append({
            "seq": seq,
            "time": ev.get("time", ev.get("block-time", None)),
            "actor": ev.get("caller", ev.get("actor", None)),
            "action": ev.get("action", ev.get("event-type", "?")),
            "result": ev.get("result", ev.get("outcome", "?")),
            "evidence_refs": [f"evidence/events/{seq:03d}-{ev.get('action', 'event')}.json"],
        })
    return {
        "schema_version": "trace-summary.v1",
        "scenario_id": replay.get("scenario-id"),
        "scenario_title": replay.get("source", {}).get("description",
                         replay.get("source", {}).get("scenario-id", "unknown")),
        "outcome": replay.get("outcome", "unknown"),
        "events_processed": replay.get("events-processed", len(trace)),
        "steps": steps,
        **derived_from(replay_path),
    }


def extract_metrics(replay: dict, replay_path: pathlib.Path) -> dict:
    metrics = replay.get("metrics", {})
    return {
        "schema_version": "scenario-metrics.v1",
        "scenario_id": replay.get("source", {}).get("scenario-id"),
        "outcome": replay.get("outcome", "unknown"),
        "events_processed": replay.get("events-processed", 0),
        "metrics": {
            "escrow_unrealized": metrics.get("escrow-unrealized", 0),
            "escrow_realized": metrics.get("escrow-realized", 0),
            "invariant_violations": metrics.get("invariant-violations", 0),
            "attack_attempts": metrics.get("attack-attempts", 0),
            "claimable": metrics.get("claimable", 0),
            "batch_conflicts": metrics.get("batch-conflicts", 0),
            "available_ratio": metrics.get("available-ratio", None),
        },
        **derived_from(replay_path),
    }


def extract_claimable_classification(replay: dict, replay_path: pathlib.Path) -> dict:
    world = replay.get("world", {})
    claimable = world.get("claimable", {})
    escrows = world.get("escrows", {})
    result = {
        "schema_version": "claimable-classification.v2",
        "scenario_id": replay.get("source", {}).get("scenario-id"),
        "claimable_entries": len(claimable) if isinstance(claimable, dict) else 0,
        "escrow_count": len(escrows) if isinstance(escrows, dict) else 0,
        **derived_from(replay_path),
    }
    test_run_path = replay_path.parent / "test-run.json"
    if test_run_path.exists():
        try:
            with test_run_path.open("r") as f:
                test_run = json.load(f)
                result["run_id"] = test_run.get("run_id")
        except (json.JSONDecodeError, IOError):
            pass
    return result


def extract_mechanism_summary(replay: dict, replay_path: pathlib.Path) -> dict:
    trace = replay.get("trace", [])
    metrics = replay.get("metrics", {})
    temporal_ok = all(ev.get("time") is not None or ev.get("block-time") is not None for ev in trace)
    return {
        "schema_version": "mechanism-summary.v1",
        "scenario_id": replay.get("source", {}).get("scenario-id"),
        "outcome": replay.get("outcome", "unknown"),
        "escrow": {
            "created": sum(1 for ev in trace if ev.get("action") == "create_escrow"),
            "released": sum(1 for ev in trace if ev.get("action") == "release_escrow"),
            "disputed": sum(1 for ev in trace if ev.get("action") == "raise_dispute"),
            "finalized": sum(1 for ev in trace if ev.get("action") in ("release_escrow", "release_claimable", "finalize_claimable")),
        },
        "dispute": {
            "raised": sum(1 for ev in trace if ev.get("action") == "raise_dispute"),
            "resolved": sum(1 for ev in trace if ev.get("action") == "execute_resolution"),
            "appealed": sum(1 for ev in trace if ev.get("action") == "escalate_dispute"),
            "outcome": replay.get("outcome"),
        },
        "slashing": {
            "attempts": sum(1 for ev in trace if ev.get("action") == "propose_fraud_slash"),
            "executed": sum(1 for ev in trace if ev.get("action") == "execute_fraud_slash"),
            "unmet_obligations": metrics.get("unrealized", 0),
        },
        "claimable": {
            "total_claimable": metrics.get("claimable", 0),
            "stale_claimables": 0,
        },
        "temporal": {
            "steps": len(trace),
            "clock_mode": "discrete-step",
            "consistent": temporal_ok,
        },
        **derived_from(replay_path),
    }


def extract_trace_plain(replay: dict, replay_path: pathlib.Path) -> str:
    trace = replay.get("trace", [])
    lines = ["# Plain Language Trace Summary\n"]
    for i, ev in enumerate(trace):
        action = ev.get("action", "unknown")
        outcome = ev.get("outcome", "success")
        desc = ACTION_DESCRIPTIONS.get(action, f"Performed unknown action: {action}")
        if outcome != "success":
            desc = f"{desc} (Status: {outcome})"
        lines.append(f"{i+1}. **{action}**: {desc}")
    return "\n".join(lines)


def extract_final_world(replay: dict, replay_path: pathlib.Path) -> dict:
    world = replay.get("world", {})
    return {
        "schema_version": "world-final.v1",
        "scenario_id": replay.get("source", {}).get("scenario-id"),
        "outcome": replay.get("outcome", "unknown"),
        "events_processed": replay.get("events-processed", 0),
        "world": world,
        **derived_from(replay_path),
    }


def extract_schema_map(replay: dict, replay_path: pathlib.Path) -> dict:
    return {
        "schema_version": "schema-map.v1",
        "map": SCHEMA_MAP,
        **derived_from(replay_path),
    }


# ── Main ─────────────────────────────────────────────────────────────────────


def _now_utc() -> str:
    """Return ISO-8601 UTC timestamp."""
    from datetime import datetime, timezone
    return datetime.now(timezone.utc).isoformat()


def _sha256_path(path: pathlib.Path) -> str | None:
    return sha256_file(path)


def _artifact_entry(artifact_id: str, kind: str, importance: str,
                    schema_version: str, path: pathlib.Path) -> dict:
    """Build an artifact registry entry for one generated file."""
    return {
        "id": artifact_id,
        "kind": kind,
        "path": str(path),
        "importance": importance,
        "schema_version": schema_version,
        "contract_version": "evidence-contract.v1",
        "producer": "extract-scenario-artifacts.v1",
        "verifies_against": [],
        "dependencies": [],
        "sha256": _sha256_path(path),
        "bytes": path.stat().st_size if path.exists() else 0,
        "mtime_utc": _now_utc(),
    }


def update_artifact_registry(run_dir: pathlib.Path, new_entries: list[dict]):
    """Append new entries to test-artifacts.json in the run directory."""
    registry_path = run_dir / "test-artifacts.json"
    if not registry_path.exists():
        return
    with registry_path.open("r", encoding="utf-8") as f:
        registry = json.load(f)
    existing_ids = {a["id"] for a in registry.get("artifacts", [])}
    added = 0
    for entry in new_entries:
        if entry["id"] not in existing_ids:
            registry.setdefault("artifacts", []).append(entry)
            existing_ids.add(entry["id"])
            added += 1
    if added:
        registry["generated_at"] = _now_utc()
        with registry_path.open("w", encoding="utf-8") as f:
            json.dump(registry, f, indent=2)
        print(f"  [registry] Added {added} new artifact(s) to test-artifacts.json")


def main():
    parser = argparse.ArgumentParser(
        description="Extract scenario-scoped artifacts from replay output JSON")
    parser.add_argument("--replay", required=True,
                        help="Path to replay output JSON (--output-file)")
    parser.add_argument("--run-dir", required=True,
                        help="Run-specific directory (e.g. results/runs/s19-.../)")
    args = parser.parse_args()

    replay_path = pathlib.Path(args.replay)
    run_dir = pathlib.Path(args.run_dir)

    if not replay_path.exists():
        print(f"Error: replay file not found: {replay_path}", file=sys.stderr)
        sys.exit(1)

    with replay_path.open("r", encoding="utf-8") as f:
        replay = json.load(f)

    # 1. raw/replay-output.json — copy of original output
    write_json(run_dir / "raw" / "replay-output.json", replay)
    print(f"  raw/replay-output.json ({os.path.getsize(run_dir / 'raw' / 'replay-output.json')} bytes)")

    # 2. summaries/trace-summary.json
    trace_summ = extract_trace_summary(replay, replay_path)
    write_json(run_dir / "summaries" / "trace-summary.json", trace_summ)
    print(f"  summaries/trace-summary.json ({len(trace_summ['steps'])} events)")

    # 3. summaries/metrics.json
    metrics = extract_metrics(replay, replay_path)
    write_json(run_dir / "summaries" / "metrics.json", metrics)
    print(f"  summaries/metrics.json ({len(metrics['metrics'])} metrics)")

    # 4. summaries/claimable-classification.json
    claimable = extract_claimable_classification(replay, replay_path)
    write_json(run_dir / "summaries" / "claimable-classification.json", claimable)
    print(f"  summaries/claimable-classification.json")

    # 5. summaries/mechanism-summary.json
    mech_summ = extract_mechanism_summary(replay, replay_path)
    write_json(run_dir / "summaries" / "mechanism-summary.json", mech_summ)
    print(f"  summaries/mechanism-summary.json")

    # 6. state/world-final.json
    final_world = extract_final_world(replay, replay_path)
    write_json(run_dir / "state" / "world-final.json", final_world)
    print(f"  state/world-final.json ({len(json.dumps(final_world.get('world', {})))} bytes)")

    # 7. summaries/schema-map.json
    schema_map = extract_schema_map(replay, replay_path)
    write_json(run_dir / "summaries" / "schema-map.json", schema_map)
    print(f"  summaries/schema-map.json")

    # 8. summaries/trace-plain.md
    trace_plain = extract_trace_plain(replay, replay_path)
    with (run_dir / "summaries" / "trace-plain.md").open("w") as f:
        f.write(trace_plain)
    print(f"  summaries/trace-plain.md")

    print(f"\nArtifacts written to {run_dir}")

    # 9. Register new artifacts in test-artifacts.json
    new_entries = [
        _artifact_entry("raw.replay-output", "raw.replay", "DIAGNOSTIC",
                        "raw-replay.v1", run_dir / "raw" / "replay-output.json"),
        _artifact_entry("summaries.trace", "summary.trace", "CORE",
                        "trace-summary.v1", run_dir / "summaries" / "trace-summary.json"),
        _artifact_entry("summaries.trace-plain", "summary.trace-plain", "DIAGNOSTIC",
                        "trace-plain.v1", run_dir / "summaries" / "trace-plain.md"),
        _artifact_entry("summaries.metrics", "summary.metrics", "CORE",
                        "scenario-metrics.v1", run_dir / "summaries" / "metrics.json"),
        _artifact_entry("summaries.claimable", "summary.claimable", "CORE",
                        "claimable-classification.v2", run_dir / "summaries" / "claimable-classification.json"),
        _artifact_entry("summaries.mechanisms", "summary.mechanisms", "CORE",
                        "mechanism-summary.v1", run_dir / "summaries" / "mechanism-summary.json"),
        _artifact_entry("summaries.schema-map", "summary.schema-map", "DIAGNOSTIC",
                        "schema-map.v1", run_dir / "summaries" / "schema-map.json"),
        _artifact_entry("state.world-final", "state.final", "CORE",
                        "world-final.v1", run_dir / "state" / "world-final.json"),
    ]
    update_artifact_registry(run_dir, new_entries)

    return 0


if __name__ == "__main__":
    sys.exit(main())
