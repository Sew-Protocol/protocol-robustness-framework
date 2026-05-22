"""
Parameterized adversarial profitability sweeps.

Explores rational-attack profitability surfaces across:
- resolver fee levels
- appeal window latency
- arbitrator capacity
- escalation budget (max escalations)

Outputs:
  results/profitability-surfaces/<ts>/surface.csv
  results/profitability-surfaces/<ts>/surface.json
  results/profitability-surfaces/<ts>/regions.json
  results/profitability-surfaces/<ts>/promotions.json
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
from pathlib import Path
from typing import Any

from sim_api.live_agents import (
    GriefingBuyerLive,
    ProfitThresholdResolver,
    EscalatingBuyerLive,
    HonestResolverLive,
    AutomateTimedActionsLive,
    CapacityLimitedArbitrator,
)
from sim_api.live_runner import LiveRunner
from sim_api.grpc_client import SimulationClient


def _ts() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def _run_once(name: str, agents_meta: list[dict], live_agents: list, params: dict, max_steps=80, max_ticks=40):
    with SimulationClient() as client:
        rr = LiveRunner(client, agents_meta, live_agents, protocol_params=params)
        return rr.run(session_id=f"sweep-{name}-{_ts()}", max_steps=max_steps, max_ticks=max_ticks)


def sweep_rows() -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    fee_grid = [50, 100, 150, 300]
    latency_grid = [0, 60, 300]
    capacity_grid = [1, 2, 4]
    escalation_grid = [0, 1, 2]

    # Family A: profit-threshold strike (S30-style)
    for fee in fee_grid:
        for lat in latency_grid:
            params = {
                "resolver_fee_bps": fee,
                "appeal_window_duration": lat,
                "max_dispute_duration": 2592000,
                "dispute_resolver": "0xresolver",
            }
            buyer = GriefingBuyerLive("buyer", recipient_address="0xseller", amount=100)
            resolver = ProfitThresholdResolver("resolver", fee_bps=fee, min_profit_abs=5)
            res = _run_once(
                "f7",
                [
                    {"id": "buyer", "address": "0xbuyer", "type": "honest"},
                    {"id": "seller", "address": "0xseller", "type": "honest"},
                    {"id": "resolver", "address": "0xresolver", "type": "resolver"},
                ],
                [buyer, resolver],
                params,
                max_steps=30,
                max_ticks=12,
            )
            m = res.metrics
            rows.append({
                "family": "f7-profit-threshold-strike",
                "fee_bps": fee,
                "latency_s": lat,
                "capacity": None,
                "escalation_budget": None,
                "attack_attempts": m.get("attack_attempts", 0),
                "attack_successes": m.get("attack_successes", 0),
                "resolutions_executed": m.get("resolutions_executed", 0),
                "reverts": m.get("reverts", 0),
                "invariant_violations": m.get("invariant_violations", 0),
                "profitability_score": float(resolver.refusals),
                "unsafe": resolver.refusals > 0,
                "halt_reason": res.halt_reason,
            })

    # Family B: escalation economics (S31-style)
    for fee in fee_grid:
        for lat in latency_grid:
            for esc in escalation_grid:
                params = {
                    "resolver_fee_bps": fee,
                    "appeal_window_duration": lat,
                    "max_dispute_duration": 2592000,
                    "resolution_module": "0xmulti",
                    "escalation_resolvers": {"0": "0xl0", "1": "0xl1"},
                }
                buyer = EscalatingBuyerLive("buyer", recipient_address="0xseller", amount=5000, max_escalations=esc)
                l0 = HonestResolverLive("l0")
                l1 = HonestResolverLive("l1")
                keeper = AutomateTimedActionsLive("keeper")
                res = _run_once(
                    "f8",
                    [
                        {"id": "buyer", "address": "0xbuyer", "type": "honest"},
                        {"id": "seller", "address": "0xseller", "type": "honest"},
                        {"id": "l0", "address": "0xl0", "type": "resolver"},
                        {"id": "l1", "address": "0xl1", "type": "resolver"},
                        {"id": "keeper", "address": "0xkeeper", "type": "keeper"},
                    ],
                    [buyer, l0, l1, keeper],
                    params,
                    max_steps=80,
                    max_ticks=40,
                )
                m = res.metrics
                score = float(max(0, m.get("resolutions_executed", 0) - 1))
                rows.append({
                    "family": "f8-appeal-fee-amplification",
                    "fee_bps": fee,
                    "latency_s": lat,
                    "capacity": None,
                    "escalation_budget": esc,
                    "attack_attempts": m.get("attack_attempts", 0),
                    "attack_successes": m.get("attack_successes", 0),
                    "resolutions_executed": m.get("resolutions_executed", 0),
                    "reverts": m.get("reverts", 0),
                    "invariant_violations": m.get("invariant_violations", 0),
                    "profitability_score": score,
                    "unsafe": score > 0,
                    "halt_reason": res.halt_reason,
                })

    # Family C: capacity drain (S33-style)
    for fee in fee_grid:
        for cap in capacity_grid:
            params = {
                "resolver_fee_bps": fee,
                "appeal_window_duration": 0,
                "max_dispute_duration": 2592000,
                "dispute_resolver": "0xcaplimited",
            }
            buyers = [GriefingBuyerLive(f"buyer{i}", recipient_address="0xseller", amount=500) for i in range(1, 5)]
            arb = CapacityLimitedArbitrator("arbitrator", capacity=cap)
            res = _run_once(
                "f10",
                [{"id": f"buyer{i}", "address": f"0xbuyer{i}", "type": "honest"} for i in range(1, 5)]
                + [{"id": "seller", "address": "0xseller", "type": "honest"},
                   {"id": "arbitrator", "address": "0xcaplimited", "type": "resolver"}],
                buyers + [arb],
                params,
                max_steps=90,
                max_ticks=40,
            )
            m = res.metrics
            unresolved_proxy = max(0, m.get("disputes_triggered", 0) - m.get("resolutions_executed", 0))
            rows.append({
                "family": "f10-cascade-escalation-drain",
                "fee_bps": fee,
                "latency_s": 0,
                "capacity": cap,
                "escalation_budget": None,
                "attack_attempts": m.get("attack_attempts", 0),
                "attack_successes": m.get("attack_successes", 0),
                "resolutions_executed": m.get("resolutions_executed", 0),
                "reverts": m.get("reverts", 0),
                "invariant_violations": m.get("invariant_violations", 0),
                "profitability_score": float(unresolved_proxy),
                "unsafe": unresolved_proxy > 0,
                "halt_reason": res.halt_reason,
            })

    return rows


def build_regions(rows: list[dict[str, Any]]) -> dict[str, Any]:
    out: dict[str, Any] = {"families": {}}
    for r in rows:
        fam = r["family"]
        out["families"].setdefault(fam, {"safe": 0, "unsafe": 0, "borderline": 0, "unsafe_points": []})
        if r["unsafe"]:
            out["families"][fam]["unsafe"] += 1
            out["families"][fam]["unsafe_points"].append({
                "fee_bps": r["fee_bps"],
                "latency_s": r["latency_s"],
                "capacity": r["capacity"],
                "escalation_budget": r["escalation_budget"],
                "profitability_score": r["profitability_score"],
            })
        elif r.get("halt_reason"):
            out["families"][fam]["borderline"] += 1
        else:
            out["families"][fam]["safe"] += 1
    return out


def promote(rows: list[dict[str, Any]], top_n: int = 10) -> list[dict[str, Any]]:
    ranked = sorted(rows, key=lambda r: (float(r.get("profitability_score", 0)), bool(r.get("unsafe"))), reverse=True)
    promos = []
    for r in ranked[:top_n]:
        promos.append({
            "family": r["family"],
            "risk_score": r["profitability_score"],
            "unsafe": r["unsafe"],
            "params": {
                "fee_bps": r["fee_bps"],
                "latency_s": r["latency_s"],
                "capacity": r["capacity"],
                "escalation_budget": r["escalation_budget"],
            },
            "fixture_backlog_target": "data/fixtures/traces/regression/",
        })
    return promos


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--top-n", type=int, default=10)
    args = ap.parse_args()

    ts = _ts()
    out_dir = Path("results") / "profitability-surfaces" / ts
    out_dir.mkdir(parents=True, exist_ok=True)

    rows = sweep_rows()
    fields = [
        "family", "fee_bps", "latency_s", "capacity", "escalation_budget",
        "attack_attempts", "attack_successes", "resolutions_executed", "reverts",
        "invariant_violations", "profitability_score", "unsafe", "halt_reason",
    ]
    with (out_dir / "surface.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for r in rows:
            w.writerow(r)

    regions = build_regions(rows)
    promotions = promote(rows, top_n=args.top_n)

    (out_dir / "surface.json").write_text(json.dumps({"generated_at": ts, "rows": rows}, indent=2))
    (out_dir / "regions.json").write_text(json.dumps(regions, indent=2))
    (out_dir / "promotions.json").write_text(json.dumps({"top": promotions}, indent=2))

    print(f"Wrote profitability surface artifacts to: {out_dir}")


if __name__ == "__main__":
    main()
