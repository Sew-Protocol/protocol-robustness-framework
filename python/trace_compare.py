#!/usr/bin/env python3
"""
Compare two SEW replay trace JSON files (baseline vs candidate) and emit:
  - JSON summary
  - Markdown summary (research-shareable)
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


KEY_METRICS = [
    "total-escrows",
    "total-volume",
    "disputes-triggered",
    "resolutions-executed",
    "pending-settlements-executed",
    "attack-attempts",
    "attack-successes",
    "rejected-attacks",
    "reverts",
    "invariant-violations",
    "double-settlements",
    "invalid-state-transitions",
    "funds-lost",
]

ACTION_TO_METRIC = {
    "create_escrow": "total-escrows",
    "raise_dispute": "disputes-triggered",
    "execute_resolution": "resolutions-executed",
    "execute_pending_settlement": "pending-settlements-executed",
}


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def metric_delta(base: dict[str, Any], cand: dict[str, Any]) -> dict[str, dict[str, int]]:
    out: dict[str, dict[str, int]] = {}
    bm = base.get("metrics", {}) or {}
    cm = cand.get("metrics", {}) or {}
    for k in KEY_METRICS:
        b = int(bm.get(k, 0) or 0)
        c = int(cm.get(k, 0) or 0)
        out[k] = {"baseline": b, "candidate": c, "delta": c - b}
    return out


def scenario_like_metrics(doc: dict[str, Any]) -> dict[str, int]:
    """Fallback metrics for scenario-definition trace files (fixtures)."""
    metrics = {k: 0 for k in KEY_METRICS}
    events = doc.get("events", []) or []
    for ev in events:
        action = str(ev.get("action", ""))
        mk = ACTION_TO_METRIC.get(action)
        if mk:
            metrics[mk] += 1
        if action in {"execute_resolution", "escalate_dispute", "raise_dispute"}:
            metrics["attack-attempts"] += 1
        amt = ev.get("params", {}).get("amount", 0)
        if action == "create_escrow" and isinstance(amt, (int, float)):
            metrics["total-volume"] += int(amt)
    return metrics


def extract_metrics(doc: dict[str, Any]) -> dict[str, int]:
    # replay-result shape
    if isinstance(doc.get("metrics"), dict):
        m = doc["metrics"]
        return {k: int(m.get(k, 0) or 0) for k in KEY_METRICS}
    # fixture scenario shape
    return scenario_like_metrics(doc)


def extract_outcome(doc: dict[str, Any]) -> str:
    return str(doc.get("outcome") or doc.get("purpose") or "scenario-definition")


def extract_events_processed(doc: dict[str, Any]) -> int:
    if "events-processed" in doc:
        return int(doc.get("events-processed", 0) or 0)
    return len(doc.get("events", []) or [])


def terminal_state_counts(trace: list[dict[str, Any]]) -> dict[str, int]:
    if not trace:
        return {}
    world = trace[-1].get("world", {}) or {}
    states = world.get("live-states", {}) or {}
    counts: dict[str, int] = {}
    for _wf, st in states.items():
        s = str(st)
        counts[s] = counts.get(s, 0) + 1
    return counts


def scenario_terminal_state_counts(doc: dict[str, Any]) -> dict[str, int]:
    events = doc.get("events", []) or []
    counts: dict[str, int] = {}
    for ev in events:
        action = str(ev.get("action", ""))
        if action in {"release", "execute_pending_settlement"}:
            counts["likely-terminal-events"] = counts.get("likely-terminal-events", 0) + 1
    return counts


def extract_terminal_state_counts(doc: dict[str, Any]) -> dict[str, int]:
    if isinstance(doc.get("trace"), list):
        return terminal_state_counts(doc.get("trace", []) or [])
    return scenario_terminal_state_counts(doc)


def make_headline(delta: dict[str, dict[str, int]], base_outcome: str, cand_outcome: str) -> str:
    atk_attempts = delta["attack-attempts"]["delta"]
    disputes = delta["disputes-triggered"]["delta"]
    resolutions = delta["resolutions-executed"]["delta"]
    volume = delta["total-volume"]["delta"]
    return (
        f"Candidate ({cand_outcome}) vs baseline ({base_outcome}): "
        f"attack-attempts Δ={atk_attempts:+d}, disputes Δ={disputes:+d}, "
        f"resolutions Δ={resolutions:+d}, volume Δ={volume:+d}."
    )


def to_markdown(summary: dict[str, Any]) -> str:
    b = summary["baseline"]
    c = summary["candidate"]
    lines = [
        "# SEW Trace Comparison",
        "",
        f"- Baseline: `{b['path']}`",
        f"- Candidate: `{c['path']}`",
        "",
        "## Headline",
        "",
        summary["headline"],
        "",
        "## Data Mode",
        "",
        f"- Baseline mode: **{summary['baseline']['mode']}**",
        f"- Candidate mode: **{summary['candidate']['mode']}**",
    ]
    if summary["baseline"]["mode"] == "scenario-definition" or summary["candidate"]["mode"] == "scenario-definition":
        lines += [
            "",
            "> Note: one or both inputs are scenario-definition traces. Metrics are derived from event actions and are approximate.",
        ]
    lines += [
        "",
        "## Outcome",
        "",
        f"- Baseline outcome: **{b['outcome']}**",
        f"- Candidate outcome: **{c['outcome']}**",
        f"- Baseline events processed: **{b['events_processed']}**",
        f"- Candidate events processed: **{c['events_processed']}**",
        "",
        "## Key Metric Deltas",
        "",
        "| Metric | Baseline | Candidate | Δ |",
        "|---|---:|---:|---:|",
    ]
    for k, row in summary["metric_delta"].items():
        lines.append(f"| {k} | {row['baseline']} | {row['candidate']} | {row['delta']:+d} |")

    lines += [
        "",
        "## Terminal State Counts",
        "",
        "```json",
        json.dumps(summary["terminal_state_counts"], indent=2),
        "```",
        "",
    ]
    return "\n".join(lines)


def main() -> None:
    p = argparse.ArgumentParser(description="Compare baseline and candidate SEW trace JSON outputs")
    p.add_argument("--baseline", required=True, help="Path to baseline trace JSON")
    p.add_argument("--candidate", required=True, help="Path to candidate trace JSON")
    p.add_argument("--out-dir", default="results/trace-compare", help="Output directory")
    args = p.parse_args()

    baseline_path = Path(args.baseline)
    candidate_path = Path(args.candidate)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    base = load_json(baseline_path)
    cand = load_json(candidate_path)

    base_m = {"metrics": extract_metrics(base)}
    cand_m = {"metrics": extract_metrics(cand)}
    deltas = metric_delta(base_m, cand_m)

    summary = {
        "baseline": {
            "path": str(baseline_path),
            "outcome": extract_outcome(base),
            "events_processed": extract_events_processed(base),
            "mode": "replay-result" if isinstance(base.get("metrics"), dict) else "scenario-definition",
        },
        "candidate": {
            "path": str(candidate_path),
            "outcome": extract_outcome(cand),
            "events_processed": extract_events_processed(cand),
            "mode": "replay-result" if isinstance(cand.get("metrics"), dict) else "scenario-definition",
        },
        "metric_delta": deltas,
        "terminal_state_counts": {
            "baseline": extract_terminal_state_counts(base),
            "candidate": extract_terminal_state_counts(cand),
        },
    }
    summary["headline"] = make_headline(
        deltas,
        summary["baseline"]["outcome"],
        summary["candidate"]["outcome"],
    )

    json_path = out_dir / "comparison.json"
    md_path = out_dir / "comparison.md"
    json_path.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    md_path.write_text(to_markdown(summary), encoding="utf-8")

    print(f"Wrote: {json_path}")
    print(f"Wrote: {md_path}")
    print(summary["headline"])


if __name__ == "__main__":
    main()
