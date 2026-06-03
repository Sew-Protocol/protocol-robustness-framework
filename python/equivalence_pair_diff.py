#!/usr/bin/env python3
"""
Replay counterfactual A/B fixture pairs and confirm structural divergence.

Used by scripts/test.sh equivalence-new after equivalence suites pass.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from trace_compare import (
    compare_documents,
    load_json,
    replay_scenario,
    repo_root,
    write_comparison_report,
)


def scenario_id_from_fixture(obj: dict, path: Path) -> str:
    return str(obj.get("scenario-id") or obj.get("id") or path.stem.replace(".trace", ""))


def load_comparison_groups(traces_dir: Path) -> dict[str, list[dict]]:
    groups: dict[str, list[dict]] = {}
    for path in sorted(traces_dir.glob("*.trace.json")):
        try:
            obj = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            continue
        comp = obj.get("comparison")
        if not isinstance(comp, dict):
            continue
        group = str(comp.get("comparison_group", "")).strip()
        if not group:
            continue
        groups.setdefault(group, []).append(
            {
                "id": scenario_id_from_fixture(obj, path),
                "path": path,
                "comparison": comp,
            }
        )
    return groups


def member_by_variant(members: list[dict], variant: str) -> dict | None:
    for member in members:
        if str(member["comparison"].get("variant")) == variant:
            return member
    return None


def metadata_status(members: list[dict]) -> tuple[bool, bool, str]:
    if len(members) != 2:
        return False, False, "metadata-incomplete"
    a, b = members[0], members[1]
    a_cf = str(a["comparison"].get("counterfactual_of", ""))
    b_cf = str(b["comparison"].get("counterfactual_of", ""))
    reciprocal = a_cf == b["id"] and b_cf == a["id"]
    if not reciprocal:
        return True, False, "metadata-invalid"
    return True, True, "metadata-valid"


def divergence_confirmed(summary: dict) -> bool:
    structural = summary.get("structural_diff", {})
    if structural.get("projection_hash_match") is False:
        return True
    if structural.get("worlds_identical") is False:
        return True
    return False


def build_group_summary(
    group: str,
    members: list[dict],
    *,
    replay_dir: Path,
    cwd: Path,
    replay: bool,
) -> dict:
    pair_complete, reciprocal, meta_status = metadata_status(members)
    rec: dict = {
        "members": [
            {
                "id": m["id"],
                "variant": m["comparison"].get("variant"),
                "counterfactual_of": m["comparison"].get("counterfactual_of"),
                "path": str(m["path"]),
            }
            for m in members
        ],
        "pair_complete": pair_complete,
        "reciprocal": reciprocal,
        "expected_divergence": True,
        "metadata_status": meta_status,
        "status": meta_status,
    }

    if not (pair_complete and reciprocal):
        return rec

    if not replay:
        rec["status"] = "metadata-valid-pending-replay"
        return rec

    baseline = member_by_variant(members, "A") or members[0]
    candidate = member_by_variant(members, "B") or members[1]
    group_dir = replay_dir / group
    base_json = group_dir / f"{baseline['id']}.json"
    cand_json = group_dir / f"{candidate['id']}.json"

    try:
        replay_scenario(baseline["path"], base_json, cwd=cwd)
        replay_scenario(candidate["path"], cand_json, cwd=cwd)
    except Exception as exc:
        rec["status"] = "replay-failed"
        rec["replay_error"] = str(exc).strip()
        return rec

    comparison = compare_documents(
        load_json(base_json),
        load_json(cand_json),
        baseline_path=str(base_json),
        candidate_path=str(cand_json),
    )
    _, md_path = write_comparison_report(comparison, group_dir, basename="comparison")

    structural = comparison.get("structural_diff", {})
    divergence = structural.get("first_divergence") or {}
    confirmed = divergence_confirmed(comparison)

    rec["replay_diff"] = {
        "baseline_id": baseline["id"],
        "candidate_id": candidate["id"],
        "comparison_dir": str(group_dir),
        "comparison_markdown": str(md_path),
        "projection_hash_match": structural.get("projection_hash_match"),
        "worlds_identical": structural.get("worlds_identical"),
        "divergence_confirmed": confirmed,
        "first_divergence_at": divergence.get("divergence_at"),
        "first_divergence_action": divergence.get("action"),
        "headline": comparison.get("headline"),
    }
    rec["status"] = "divergence-confirmed" if confirmed else "divergence-missing"
    return rec


def generate_summary(
    traces_dir: Path,
    out_path: Path,
    *,
    replay_dir: Path,
    replay: bool,
) -> dict:
    cwd = repo_root()
    groups = load_comparison_groups(traces_dir)
    summary = {
        "groups": {},
        "group_count": len(groups),
        "replay_enabled": replay,
    }

    failures: list[str] = []
    for group, members in sorted(groups.items()):
        rec = build_group_summary(
            group,
            members,
            replay_dir=replay_dir,
            cwd=cwd,
            replay=replay,
        )
        summary["groups"][group] = rec
        if rec.get("status") == "divergence-missing":
            failures.append(group)
        if rec.get("status") == "replay-failed":
            failures.append(group)
        if not rec.get("pair_complete") or not rec.get("reciprocal"):
            failures.append(group)

    summary["ok"] = len(failures) == 0
    summary["failed_groups"] = failures
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    return summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Diff counterfactual A/B fixture pairs")
    parser.add_argument(
        "--traces-dir",
        default="data/fixtures/traces",
        help="Directory containing *.trace.json fixtures",
    )
    parser.add_argument(
        "--out",
        default="results/test-artifacts/equivalence-comparison-summary.json",
        help="Summary JSON output path",
    )
    parser.add_argument(
        "--replay-dir",
        default="results/test-artifacts/equivalence-pairs",
        help="Directory for per-group replay outputs and comparison reports",
    )
    parser.add_argument(
        "--metadata-only",
        action="store_true",
        help="Validate pair metadata only (skip replay + structural diff)",
    )
    args = parser.parse_args(argv)

    summary = generate_summary(
        Path(args.traces_dir),
        Path(args.out),
        replay_dir=Path(args.replay_dir),
        replay=not args.metadata_only,
    )
    print(f"Wrote equivalence comparison summary: {args.out}")
    for group, rec in summary["groups"].items():
        print(f"  - {group}: {rec.get('status')}")

    if not summary["ok"]:
        print("Equivalence pair diff failed for groups:", ", ".join(summary["failed_groups"]))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
