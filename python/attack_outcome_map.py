#!/usr/bin/env python3
"""
Generate shareable visual artifacts (Mermaid diagrams/tables) from SEW traces:
  1) state transition diagram (Mermaid stateDiagram-v2, action→action with frequency)
  2) adversarial profitability surface snapshot (from latest sweep CSV if available)
  3) escalation timeline with settlement outcomes (Mermaid sequenceDiagram)

Scope: visual rendering only. All simulation logic lives in Clojure/Babashka.
       Python handles adversarial agent generation (gRPC) and diagram output.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
from pathlib import Path
from typing import Any


def ts() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def state_transition_diagram(traces_dir: Path) -> str:
    """Emit a Mermaid stateDiagram-v2 of action→action transitions across all traces.

    Each edge label shows the observed frequency across the fixture corpus.
    Adversarial transitions (events with adversarial?=true) are annotated with ⚠.
    """
    files = sorted(traces_dir.glob("*.trace.json"))
    pair_counts: dict[tuple[str, str], int] = {}
    adv_pairs: set[tuple[str, str]] = set()

    for f in files:
        doc = load_json(f)
        events = doc.get("events", []) or []
        for ev, nxt in zip(events, events[1:]):
            a = str(ev.get("action", "unknown"))
            b = str(nxt.get("action", "unknown"))
            pair_counts[(a, b)] = pair_counts.get((a, b), 0) + 1
            if ev.get("adversarial?") or nxt.get("adversarial?"):
                adv_pairs.add((a, b))

    # Sort by frequency descending so the diagram reads top-to-bottom by importance
    sorted_pairs = sorted(pair_counts.items(), key=lambda x: x[1], reverse=True)

    lines = [
        "# State Transition Diagram (Action → Action)",
        "",
        f"Corpus: `{traces_dir}` ({len(files)} trace files)",
        "Edge labels: observed frequency. ⚠ = at least one adversarial event in pair.",
        "",
        "```mermaid",
        "stateDiagram-v2",
        "    direction LR",
        "    [*] --> create_escrow",
    ]
    for (a, b), count in sorted_pairs:
        label = f"{count}x" + (" ⚠" if (a, b) in adv_pairs else "")
        # Mermaid state IDs cannot contain special chars — use underscores
        a_id = a.replace("-", "_").replace("?", "")
        b_id = b.replace("-", "_").replace("?", "")
        lines.append(f"    {a_id} --> {b_id} : {label}")

    terminal_actions = {"release", "refund", "auto_cancel_disputed"}
    for action in terminal_actions:
        a_id = action.replace("-", "_").replace("?", "")
        lines.append(f"    {a_id} --> [*]")

    lines += ["```", ""]
    return "\n".join(lines) + "\n"


def latest_surface_csv(results_dir: Path) -> Path | None:
    root = results_dir / "profitability-surfaces"
    if not root.exists():
        return None
    dirs = sorted([p for p in root.iterdir() if p.is_dir()])
    for d in reversed(dirs):
        c = d / "surface.csv"
        if c.exists():
            return c
    return None


def profitability_surface_snapshot(results_dir: Path) -> str:
    csv_path = latest_surface_csv(results_dir)
    if not csv_path:
        return (
            "# Adversarial Profitability Surface Snapshot\n\n"
            "No `results/profitability-surfaces/*/surface.csv` found. Run `bb adv:sweep` first.\n"
        )

    rows: list[dict[str, str]] = []
    with csv_path.open("r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        rows = list(reader)

    top = sorted(
        rows,
        key=lambda r: float(r.get("profitability_score") or 0.0),
        reverse=True,
    )[:10]

    lines = [
        "# Adversarial Profitability Surface Snapshot",
        "",
        f"Source: `{csv_path}`",
        "",
        "Top 10 risk points by `profitability_score`:",
        "",
        "| family | fee_bps | latency_s | capacity | escalation_budget | profitability_score | unsafe |",
        "|---|---:|---:|---:|---:|---:|---|",
    ]
    for r in top:
        lines.append(
            "| {family} | {fee_bps} | {latency_s} | {capacity} | {escalation_budget} | {profitability_score} | {unsafe} |".format(
                family=r.get("family", ""),
                fee_bps=r.get("fee_bps", ""),
                latency_s=r.get("latency_s", ""),
                capacity=r.get("capacity", ""),
                escalation_budget=r.get("escalation_budget", ""),
                profitability_score=r.get("profitability_score", ""),
                unsafe=r.get("unsafe", ""),
            )
        )

    return "\n".join(lines) + "\n"


def escalation_timeline(trace_file: Path) -> str:
    """Emit a Mermaid sequenceDiagram showing the escalation/settlement flow.

    Uses sequenceDiagram (universally supported) rather than timeline.
    Adversarial events are annotated with ⚠ in the note.
    """
    doc = load_json(trace_file)
    events = doc.get("events", []) or []
    description = doc.get("description", "")
    watched = {
        "create_escrow",
        "raise_dispute",
        "escalate_dispute",
        "execute_resolution",
        "execute_pending_settlement",
        "auto_cancel_disputed",
        "release",
        "refund",
        "recipient_cancel",
    }

    # Collect all participants in order of first appearance
    seen_agents: list[str] = []
    for e in events:
        agent = str(e.get("agent", "unknown"))
        if agent not in seen_agents:
            seen_agents.append(agent)

    lines = [
        "# Escalation Timeline with Settlement Outcomes",
        "",
        f"Source: `{trace_file}`",
    ]
    if description:
        lines += ["", f"_{description}_"]
    lines += [
        "",
        "```mermaid",
        "sequenceDiagram",
        "    autonumber",
    ]
    for agent in seen_agents:
        lines.append(f"    participant {agent}")
    lines.append("    participant protocol")

    for e in events:
        action = str(e.get("action", "unknown"))
        if action not in watched:
            continue
        seq = e.get("seq", "?")
        t = e.get("time", "?")
        agent = str(e.get("agent", "unknown"))
        is_adv = e.get("adversarial?", False)
        adv_tag = " ⚠ adversarial" if is_adv else ""
        lines.append(f"    {agent}->>protocol: {action} (t={t}){adv_tag}")
        if is_adv:
            lines.append(f"    Note over {agent},protocol: seq={seq} adversarial event")

    lines += ["```", ""]
    return "\n".join(lines)


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--traces-dir", default="data/fixtures/traces")
    p.add_argument(
        "--timeline-trace",
        default="data/fixtures/traces/governance-decay-exploit.trace.json",
        help="Trace to render as escalation/settlement sequenceDiagram. "
             "Default: governance-decay-exploit (most illustrative governance failure).",
    )
    p.add_argument("--results-dir", default="results")
    p.add_argument("--out-dir", default="results/attack-outcome-map")
    args = p.parse_args()

    out_dir = Path(args.out_dir) / ts()
    out_dir.mkdir(parents=True, exist_ok=True)

    diagram_md = state_transition_diagram(Path(args.traces_dir))
    surface_md = profitability_surface_snapshot(Path(args.results_dir))
    timeline_md = escalation_timeline(Path(args.timeline_trace))

    (out_dir / "state-transition-diagram.md").write_text(diagram_md, encoding="utf-8")
    (out_dir / "profitability-surface-snapshot.md").write_text(surface_md, encoding="utf-8")
    (out_dir / "escalation-timeline.md").write_text(timeline_md, encoding="utf-8")

    print(f"Wrote visual artifacts to: {out_dir}")
    print("  state-transition-diagram.md   — Mermaid stateDiagram-v2")
    print("  profitability-surface-snapshot.md — top risk points table")
    print("  escalation-timeline.md        — Mermaid sequenceDiagram")


if __name__ == "__main__":
    main()
