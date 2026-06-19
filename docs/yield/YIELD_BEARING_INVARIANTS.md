# Yield-Bearing Asset Invariants

Yield-bearing scenarios extend the Sew protocol invariants with yield-specific
edge cases where escrows carry on-chain yield positions. Value can change
through index movement, shortfalls, haircuts, and accrual quantization.

## Category Definition

A scenario is **yield-bearing** when the escrow's `yield-preset ≠ :off` and
a yield module (`aave-v3`, `fixed-rate`, etc.) is configured. The escrow
principal is deposited into the yield module at creation; yield accrues
over time; withdrawal at finalization crystallizes the yield position.

## Scenario Map

| ID | Stress theme | Invariant result |
|----|-------------|-----------------|
| S113 | Principal-loss haircut (`recoverable: false`) | `solvency :fail` (by-design) |
| S115 | Historical index replay (index-schedule) | All pass |

### S113 — Principal-Loss Haircut

**Mechanism:** Liquidity schedule drops `available-ratio` to 0.5 at t=1500.
Shortfall model `{:type :principal-loss, :recoverable false}` converts the
unavailable remainder into a permanent haircut (2488 of 4975 USDC).

**Invariant behavior:**

| Invariant | Status | Reason |
|-----------|--------|--------|
| `:conservation-of-funds` | `:pass` | Loss recognized via `sum-recognized-losses` (2488), subtracted from inflow |
| `:token-tax-reconciliation` | `:pass` | Transition deltas balance after losses term |
| `:held-delta-accounted` | `:pass` | Losses term added to delta-inflow calculation |
| `:solvency` | **`:fail`** | **By-design.** Haircut destroys 2488 units; total-assets (5025) < total-inflows (5000), ratio < 1.0. Inherent to any principal-loss scenario — value destruction necessarily breaks the solvency invariant. |

**Required scenario setup:** `yield-preset: "to-sender"` in `create_escrow`
params (yield is disabled by default — `normalize-yield-preset(nil)` = `:off`).

### S115 — Historical Index Replay

**Mechanism:** External index-schedule (`aave_historical_v3.json`) drives
index from 1.0 → 1.012 over 19000 seconds. No APY is configured; the
index is read directly from the schedule. 59 USDC yield crystallized on
release.

**Invariant behavior:** All pass (0 violations).

**Critical fixes applied:**

| Component | Issue | Fix |
|-----------|-------|-----|
| `accrue` | Index-schedule never read — APY=0 produced zero accrual | `accrue` now calls `market-state/get-market-state` and uses `(:index ms)` directly via `update-position-yield` |
| `withdraw` | Realized-yield capped at `(- fulfilled-total principal)` = 0 under `:not-claimable` waterfall policy | When no shortfall, realized-yield = full `unrealized-yield` |
| `registry.clj` | External JSON schedule `:type` left as string `"steps"` — `get-value-at-time` pattern-matched on keyword `:steps` | `some->` with `update :type keyword` after `load-external-json` |
| `single-resolution-payout-consistent` | Invariant checked flat `:claimable` map — yield distribution to sender appeared as second payout direction | Switched to v2 domain-level check (`:claimable-v2`); each domain independent |

## Yield-Specific Sew Invariants

### World invariants (checked `check-all`)

| ID | Check | Yield-bearing relevance |
|----|-------|------------------------|
| `:yield-position-consistency` | Principal/shares/realized ≥ 0; MTM allows negative unrealized | Catches position corruption after shortfall/haircut |
| `:yield-exposure` | `:yield/held-balances` ≥ active position custody need | Prevents over-distribution relative to module liquidity |
| `:conservation-of-funds` | inflow = principal + yield - losses = accounted | Principal-loss scenarios produce recognized losses |
| `:solvency` | total-assets / total-inflows ≥ 1.0 | **By-design failure** when value is permanently destroyed |

### Transition invariants (checked `check-transition`)

| ID | Check | Yield-bearing relevance |
|----|-------|------------------------|
| `:token-tax-reconciliation` | No unexplained held decrease | Yield changes and haircuts create deltas that must be accounted |
| `:held-delta-accounted` | delta-held = delta-inflow - delta-outflow | Losses term distinguishes yield haircut from corruption |
| `:single-resolution-payout-consistent` | Each v2 claimable domain has ≤ 1 positive claimant | Yield distribution creates separate `:settlement/yield` domain |

## Remaining Gaps

| Gap | Scenario | Nature |
|-----|----------|--------|
| `:solvency` failure | S113 | **By-design.** No code fix possible — principal-loss inherently destroys value. Solvency is a KPI, not a soundness invariant. |

## Reference

- Yield-general invariants: `docs/yield/YIELD_INVARIANTS.md`
- Invariant catalog: `src/resolver_sim/yield/invariant_catalog.clj`
- Sew invariants: `src/resolver_sim/protocols/sew/invariants.clj`
- Yield scenarios: `scenarios/S113_principal-loss-haircut.json`, `scenarios/S115_historical-index-replay.json`
