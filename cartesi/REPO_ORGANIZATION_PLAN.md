# SEW On-Chain Repository Organization Plan

## Goal

Remove duplicated verification/scenario artifacts, establish a single source of truth for scenario authoring, and keep Cartesi-specific execution tooling cleanly separated.

## Current Findings

- Duplicate verification suite directories exist:
  - `sew-onchain/verification-suite/` (scripts + scenarios + outputs)
  - `sew-onchain/sew-simulation/verification-suite/` (scenarios + outputs)
- Duplicate scenario directories exist:
  - `sew-onchain/verification-suite/scenarios/` (41 scenarios)
  - `sew-onchain/sew-simulation/verification-suite/scenarios/` (42 scenarios; includes `S42_resolver-buyer-bribery-loop.json`)
  - `sew-onchain/sew-simulation/scenarios/` (41 scenarios, filenames with double-space style, e.g. `S01  baseline-happy-path.json`)

## Recommended Canonical Ownership

### 1) Simulation-owned source of truth

Use `sew-onchain/sew-simulation/scenarios/` as the authoritative location for scenario definitions.

Reasoning:
- Scenarios are part of protocol simulation/validation domain logic.
- They evolve with simulation engine and invariants.
- Keeps authoring close to model/test code.

### 2) Cartesi suite as execution harness only

Retain `sew-onchain/verification-suite/` for:
- `run_all.py`
- `fetch_results.py`
- `INSTRUCTIONS.md`
- runtime `outputs/`

and **stop storing a second authoritative copy** of scenarios there.

## Recommended Repository Strategy

## Keep `cartesi-app` as subdirectory in current main repo (for now)

This is the best fit now because:
- Scenario + simulation + Cartesi adapter currently change together.
- One PR can update simulation behavior and on-chain harness consistently.
- Avoids premature split complexity (versioning artifacts, cross-repo CI, synchronization overhead).

### Revisit separate repo only if

Split `cartesi-app` into its own repo when at least one is true:
- Different team ownership or release cadence.
- External/public distribution requires independent lifecycle.
- CI/dependency/runtime isolation becomes materially beneficial.

If split later, publish scenario artifacts as versioned releases (or a package) and consume by explicit version.

## Target Structure

```text
cartesi-app/sew-onchain/
  verification-suite/
    run_all.py
    fetch_results.py
    INSTRUCTIONS.md
    outputs/                   # generated artifacts only
  sew-simulation/
    scenarios/                 # canonical scenario source (authoritative)
```

## Minimal-Disruption Migration Plan

1. **Normalize naming convention** in canonical scenario source
   - Choose one style (`S01_baseline-happy-path.json` recommended).
   - Rename files in `sew-simulation/scenarios/` from double-space style.

2. **Add Cartesi-side sync/resolve mechanism**
   - Option A (preferred): update `run_all.py` to read directly from `../sew-simulation/scenarios`.
   - Option B: add `sync_scenarios.py` to copy canonical files into local `verification-suite/scenarios/` before submission.

3. **Backfill S42 into canonical source**
   - Move `S42_resolver-buyer-bribery-loop.json` into canonical `sew-simulation/scenarios/`.
   - Ensure both simulation and Cartesi harness can run it.

4. **Deprecate duplicate directory**
   - Remove `sew-simulation/verification-suite/scenarios/` after Step 2 is stable.
   - Keep only one persistent scenario source.

5. **Treat outputs as generated artifacts**
   - Keep outputs where operators expect them (`verification-suite/outputs`).
   - Prefer `.gitignore` for outputs unless snapshot retention is explicitly needed.

6. **Update docs and contributor guidance**
   - `README.md` (top-level `sew-onchain`) should state ownership boundaries.
   - `verification-suite/INSTRUCTIONS.md` should describe canonical scenario path and run flow.

## Success Criteria

- Exactly one authoritative scenario directory.
- `run_all.py` executes against canonical source without manual copying confusion.
- No duplicate `verification-suite/scenarios` trees drifting apart.
- S42 status is explicit (adopted into canonical set or intentionally excluded with rationale).

## Suggested Execution Order for Implementation PR

1. Introduce canonical path in scripts (backward-compatible).
2. Add/move S42 and normalize names.
3. Update docs.
4. Remove duplicate scenario tree.
5. Optional: clean historical duplicated outputs.
