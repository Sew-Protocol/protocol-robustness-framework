#!/usr/bin/env python3
"""
Compare two simulation replay trace JSON files (baseline vs candidate) and emit:
  - JSON summary
  - Markdown summary (research-shareable)
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


# Dynamic metric extraction: union of all keys found in both files
def extract_metrics(doc: dict[str, Any]) -> dict[str, int]:
    # replay-result shape
    if isinstance(doc.get("metrics"), dict):
        return {k: int(v or 0) for k, v in doc["metrics"].items()}
    # fallback: empty metrics if not a replay result
    return {}

def metric_delta(base_m: dict[str, int], cand_m: dict[str, int]) -> dict[str, dict[str, int]]:
    out: dict[str, dict[str, int]] = {}
    all_keys = sorted(set(base_m.keys()) | set(cand_m.keys()))
    for k in all_keys:
        b = base_m.get(k, 0)
        c = cand_m.get(k, 0)
        out[k] = {"baseline": b, "candidate": c, "delta": c - b}
    return out

def extract_outcome(doc: dict[str, Any]) -> str:
    return str(doc.get("outcome") or doc.get("purpose") or "unknown")

def extract_events_processed(doc: dict[str, Any]) -> int:
    if "events-processed" in doc:
        return int(doc.get("events-processed", 0) or 0)
    return len(doc.get("events", []) or doc.get("trace", []) or [])

def extract_terminal_entity_count(doc: dict[str, Any]) -> int:
    """Extract entity count from terminal world view if available."""
    trace = doc.get("trace", [])
    if not trace:
        return 0
    # Try to find generic world_view entity_count
    last_step = trace[-1]
    world = last_step.get("world", {}) or {}
    return int(world.get("entity_count", 0) or 0)

def make_headline(delta: dict[str, dict[str, int]], base_outcome: str, cand_outcome: str) -> str:
    # Pick top 3 non-zero deltas for the headline
    non_zero = {k: v["delta"] for k, v in delta.items() if v["delta"] != 0}
    sorted_deltas = sorted(non_zero.items(), key=lambda x: abs(x[1]), reverse=True)[:3]
    delta_str = ", ".join([f"{k} Δ={v:+d}" for k, v in sorted_deltas])
    
    if not delta_str:
        delta_str = "no metric deltas"
        
    return (
        f"Candidate ({cand_outcome}) vs baseline ({base_outcome}): "
        f"{delta_str}."
    )

def to_markdown(summary: dict[str, Any]) -> str:
    b = summary["baseline"]
    c = summary["candidate"]
    
    # Identify Funds-related metrics for a dedicated table
    FUNDS_KEYWORDS = {"volume", "funds", "held", "fee", "stake", "profit", "slash", "bond"}
    deltas = summary["metric_delta"]
    funds_metrics = {k: v for k, v in deltas.items() if any(kw in k.lower() for kw in FUNDS_KEYWORDS)}
    other_metrics = {k: v for k, v in deltas.items() if k not in funds_metrics}

    lines = [
        "# Simulation Regression Report",
        "",
        f"- Baseline: `{b['path']}`",
        f"- Candidate: `{c['path']}`",
        "",
        "## Summary",
        "",
        summary["headline"],
        "",
        "## Outcome Comparison",
        "",
        f"| Dimension | Baseline | Candidate | Status |",
        f"|---|---|---|---|",
        f"| **Outcome** | {b['outcome']} | {c['outcome']} | {'✅ MATCH' if b['outcome'] == c['outcome'] else '❌ DIVERGED'} |",
        f"| **Events** | {b['events_processed']} | {c['events_processed']} | {'✅ MATCH' if b['events_processed'] == c['events_processed'] else '⚠️ DIFF'} |",
        f"| **Entities** | {summary['terminal_entity_counts']['baseline']} | {summary['terminal_entity_counts']['candidate']} | {'✅ MATCH' if summary['terminal_entity_counts']['baseline'] == summary['terminal_entity_counts']['candidate'] else '⚠️ DIFF'} |",
    ]

    if funds_metrics:
        lines += [
            "",
            "## 💰 Funds & Economic Usage",
            "",
            "| Economic Metric | Baseline | Candidate | Δ |",
            "|---|---:|---:|---:|",
        ]
        for k, row in sorted(funds_metrics.items()):
            lines.append(f"| {k} | {row['baseline']} | {row['candidate']} | {row['delta']:+d} |")

    lines += [
        "",
        "## 📊 Other Lifecycle Metrics",
        "",
        "| Metric | Baseline | Candidate | Δ |",
        "|---|---:|---:|---:|",
    ]
    for k, row in sorted(other_metrics.items()):
        lines.append(f"| {k} | {row['baseline']} | {row['candidate']} | {row['delta']:+d} |")

    return "\n".join(lines)


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def main() -> None:
    p = argparse.ArgumentParser(description="Compare baseline and candidate simulation trace JSON outputs")
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

    base_m = extract_metrics(base)
    cand_m = extract_metrics(cand)
    deltas = metric_delta(base_m, cand_m)

    summary = {
        "baseline": {
            "path": str(baseline_path),
            "outcome": extract_outcome(base),
            "events_processed": extract_events_processed(base),
        },
        "candidate": {
            "path": str(candidate_path),
            "outcome": extract_outcome(cand),
            "events_processed": extract_events_processed(cand),
        },
        "metric_delta": deltas,
        "terminal_entity_counts": {
            "baseline": extract_terminal_entity_count(base),
            "candidate": extract_terminal_entity_count(cand),
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
