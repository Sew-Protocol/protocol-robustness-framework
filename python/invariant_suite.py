"""
invariant_suite.py — Adversarial failure-mode scenarios (S24–S34).

Runs the F-series scenarios over the gRPC bridge.  Each scenario asserts
protocol-level security properties under adversarial agent strategies.

Scenarios S01–S23 (deterministic invariant scenarios) have been ported to
Clojure in-process execution.  Run them with:
    clojure -M:run -- --invariants

Scenarios
---------
 S24  f1-liveness-extraction        dispute flooding exhausts resolver throughput
 S25  f2-appeal-window-race         MEV front-run clears pending settlement
 S26  f3-governance-sandwich        resolver rotated mid-dispute via governance
 S27  f4-escalation-loop-amplified  repeated escalations drain resolver gas
 S28  f5-concurrent-status-desync   concurrent cancel+dispute state collisions
 S29  f6-resolver-cartel            colluding resolvers misresolve for profit
 S30  f7-profit-threshold-strike    resolver withholds service below threshold
 S31  f8-appeal-fee-amplification   appeal bonds amplify protocol fee extraction
 S32  f9-subthreshold-misresolution below-threshold disputes misresolved silently
 S33  f10-cascade-escalation-drain  cascading escalations drain arbitration pool
 S34  phase-k-auto-slash            reversal slashing ensures robustness with 0% detection

Usage:
    python python/invariant_suite.py

Requires the Clojure gRPC server:
    clojure -M:run -- -S
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import platform
import subprocess
import sys
import time
from typing import Any

from sim_api.live_runner import RunResult
from eth_failure_modes import (
    s24_f1_liveness_extraction,
    s25_f2_appeal_window_race,
    s27_f4_escalation_loop_amplification,
    s28_f5_concurrent_status_desync,
)
from eth_failure_modes_2 import (
    s29_f6_resolver_cartel,
    s30_f7_profit_threshold_strike,
    s31_f8_appeal_fee_amplification,
    s32_f9_subthreshold_misresolution,
    s33_f10_cascade_escalation_drain,
)
from phase_k_robustness import s34_phase_k_auto_slash_robustness
from phase_l_optimistic import s35_phase_l_watchtower_robustness


# ---------------------------------------------------------------------------
# Scenario registry (F-series only)
# ---------------------------------------------------------------------------

SCENARIOS = [
    ("S24  f1-liveness-extraction",        s24_f1_liveness_extraction),
    ("S25  f2-appeal-window-race",         s25_f2_appeal_window_race),
    ("S27  f4-escalation-loop-amplified",  s27_f4_escalation_loop_amplification),
    ("S28  f5-concurrent-status-desync",   s28_f5_concurrent_status_desync),
    ("S29  f6-resolver-cartel",            s29_f6_resolver_cartel),
    ("S30  f7-profit-threshold-strike",    s30_f7_profit_threshold_strike),
    ("S31  f8-appeal-fee-amplification",   s31_f8_appeal_fee_amplification),
    ("S32  f9-subthreshold-misresolution", s32_f9_subthreshold_misresolution),
    ("S33  f10-cascade-escalation-drain",  s33_f10_cascade_escalation_drain),
    ("S34  phase-k-auto-slash-robustness", s34_phase_k_auto_slash_robustness),
    ("S35  phase-l-watchtower-robustness", s35_phase_l_watchtower_robustness),
]


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _vcs_info() -> tuple[str, str]:
    try:
        from scripts.vcs_info import short_sha, branch
        return (short_sha() or "unknown",
                branch() or "unknown")
    except Exception:
        return "unknown", "unknown"


def _sum_metrics(all_results: list[dict]) -> dict:
    """Aggregate RunResult.metrics across all scenarios."""
    keys = [
        "total_escrows", "total_volume", "disputes_triggered",
        "resolutions_executed", "pending_settlements_executed",
        "attack_attempts", "attack_successes", "reverts", "invariant_violations",
    ]
    totals: dict[str, Any] = {k: 0 for k in keys}
    for r in all_results:
        m = r.get("metrics", {})
        for k in keys:
            totals[k] += m.get(k, 0)
    totals["total_transactions"] = sum(
        r.get("steps", 0) for r in all_results
    )
    return totals


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(description="SEW Adversarial Failure-Mode Suite (S24-S33)")
    parser.add_argument(
        "--scenario", "-s",
        metavar="FILTER",
        help="Run only scenarios whose name contains FILTER (case-insensitive)",
    )
    parser.add_argument(
        "--json",
        metavar="PATH",
        nargs="?",
        const="auto",
        help="Write JSON report to PATH (default: results/invariant-suite-<ts>.json)",
    )
    args = parser.parse_args()

    git_sha, git_branch = _vcs_info()
    run_at    = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    python_ver = platform.python_version()

    W = 72
    print(f"\n{'═' * W}")
    print("  SEW Invariant Suite — Adversarial Failure-Mode Scenarios (S24–S33)")
    print(f"{'═' * W}")
    print(f"  git:     {git_sha}  ({git_branch})")
    print(f"  python:  {python_ver}")
    print(f"  run at:  {run_at}")
    print(f"  note:    S01–S23 run in-process via: clojure -M:run -- --invariants")
    print(f"{'─' * W}")
    print(f"  {'Scenario':<45} {'time':>5}  {'steps':>5}  {'reverts':>7}  status")
    print(f"  {'─' * (W - 2)}")

    scenarios_to_run = [
        (name, fn) for name, fn in SCENARIOS
        if not args.scenario or args.scenario.lower() in name.lower()
    ]

    results_data: list[dict] = []
    suite_start = time.perf_counter()

    for name, fn in scenarios_to_run:
        t0  = time.perf_counter()
        result, ok = fn()
        elapsed = time.perf_counter() - t0

        results_data.append({
            "name":    name,
            "ok":      ok,
            "elapsed": round(elapsed, 2),
            "steps":   result.steps_executed,
            "outcome": result.outcome,
            "halt_reason": result.halt_reason,
            "metrics": result.metrics,
        })

    suite_elapsed = time.perf_counter() - suite_start

    passed = sum(1 for r in results_data if r["ok"])
    total  = len(results_data)
    totals = _sum_metrics(results_data)

    rejection_pct = (
        100.0 * totals["reverts"] / totals["total_transactions"]
        if totals["total_transactions"] else 0.0
    )
    attack_success_pct = (
        100.0 * totals["attack_successes"] / totals["attack_attempts"]
        if totals["attack_attempts"] else 0.0
    )

    print(f"\n{'═' * W}")
    print(f"  {passed}/{total} scenarios passed  ({suite_elapsed:.1f}s total)")
    if passed < total:
        failed = [r["name"] for r in results_data if not r["ok"]]
        print(f"  FAILED: {', '.join(failed)}")

    print(f"{'─' * W}")
    print(f"  Summary statistics")
    print(f"  {'Total transactions processed:':<40} {totals['total_transactions']:>6}")
    print(f"  {'Rejected (reverts):':<40} {totals['reverts']:>6}  ({rejection_pct:.1f}%)")
    print(f"  {'Escrows created:':<40} {totals['total_escrows']:>6}")
    print(f"  {'Total escrow volume simulated:':<40} {totals['total_volume']:>6}")
    print(f"  {'Disputes triggered:':<40} {totals['disputes_triggered']:>6}")
    print(f"  {'Resolutions executed:':<40} {totals['resolutions_executed']:>6}")
    print(f"  {'Attack attempts logged:':<40} {totals['attack_attempts']:>6}")
    print(f"  {'Attack successes:':<40} {totals['attack_successes']:>6}  ({attack_success_pct:.1f}%)")
    print(f"  {'Invariant violations:':<40} {totals['invariant_violations']:>6}")
    print(f"{'═' * W}\n")

    if args.json is not None:
        json_path = args.json if args.json != "auto" else None
        if json_path is None:
            ts = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
            os.makedirs("results", exist_ok=True)
            json_path = f"results/invariant-suite-{ts}.json"
        report = {
            "run_at":        run_at,
            "git_sha":       git_sha,
            "git_branch":    git_branch,
            "python_version": python_ver,
            "passed":        passed,
            "total":         total,
            "suite_elapsed_s": round(suite_elapsed, 2),
            "summary":       totals,
            "scenarios":     results_data,
        }
        with open(json_path, "w") as f:
            json.dump(report, f, indent=2)
        print(f"  JSON report: {json_path}\n")

    sys.exit(0 if passed == total else 1)


if __name__ == "__main__":
    main()
