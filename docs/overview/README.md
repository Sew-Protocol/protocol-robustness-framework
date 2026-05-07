# Overview

> **This file is a historical archive of the Monte Carlo incentive-economics phase.**
> For the current overview, see:
> - [docs/README.md](../README.md) — documentation index
> - [docs/overview/SIMULATION_OVERVIEW.md](SIMULATION_OVERVIEW.md) — current simulation architecture
> - [docs/RESEARCH_NOTE_V0.md](../RESEARCH_NOTE_V0.md) — research findings

The content below is preserved for historical context only. It documents the DR1/DR2/DR3 Monte Carlo phases which preceded the current protocol-adapter replay and adversarial simulation approach.

---
## Quick Start

```bash
# Clone
cd ~/Code/sew-simulation

# Run tests
./scripts/test.sh

# Run DR3 (full system - default)
./run-dr3.sh

# Run DR1 (fee-only - interim)
./run-dr1.sh

# Run DR2 (bonds + reputation - interim)
./run-dr2.sh

# Run a single scenario
clojure -M:run data/params/baseline.edn

# Run parameter sweep
clojure -M:sweep data/params/stress/cartel.edn

# Generate report
clojure -M:clerk

# Lint
clojure -M:lint

# Format
clojure -M:fmt
```

## Project Structure

- **src/resolver_sim/stochastic/** — Pure functions for dispute mechanics (economics, slashing, appeals)
- **src/resolver_sim/sim/** — Trial + batch + sweep orchestration
- **src/resolver_sim/io/** — Parameter loading, result serialization (EDN/CSV)
- **test/** — Unit tests + property-based invariants
- **data/params/** — EDN scenario definitions (baseline, whale-attack, cartel, etc.)
- **results/** — Generated outputs (gitignored)
- **notebooks/** — Clerk reports for analysis + visualization

## Architecture Principles

### Pure Model Layer

All dispute mechanics in `src/resolver_sim/stochastic/` are **deterministic functions** given:

- Parameters (escrow size, fees, bonds, slashing)
- RNG seed
- Resolver strategy

No side effects, no global state. Easy to test, parallelize, and reason about.

### Reproducibility

Every run captures:

- Full resolved parameters
- RNG seed
- Git commit hash
- Timestamp
- JVM version
- Trial counts + parallelism config

Results can be re-run bit-for-bit.

### Property-Based Testing

Tests validate invariants:

- "Honest profit > malice profit in expectation"
- "Slashing never exceeds bond"
- "Appeal rates are consistent"
- "Results don't depend on trial ordering"

## Key Files

### Model Layer

- `src/resolver_sim/stochastic/types.clj` — Parameter schemas
- `src/resolver_sim/stochastic/economics.clj` — Payoff functions
- `src/resolver_sim/stochastic/dispute.clj` — Dispute lifecycle
- `src/resolver_sim/stochastic/decision_quality.clj` — Decision quality model
- `src/resolver_sim/stochastic/difficulty.clj` — Difficulty distribution
- `src/resolver_sim/stochastic/rng.clj` — Deterministic RNG

### Simulation Layer

- `src/resolver_sim/sim/trial.clj` — One trial (one dispute outcome)
- `src/resolver_sim/sim/batch.clj` — Aggregate N trials
- `src/resolver_sim/sim/sweep.clj` — Parameter sweeps

### I/O & Reporting

- `src/resolver_sim/io/params.clj` — Load/validate parameters
- `src/resolver_sim/io/results.clj` — Serialize results
- `notebooks/report.clj` — Clerk notebook for analysis

## Parameter Scenarios

### Dispute Resolution Phases

The simulation supports three DR phases:

| Phase   | Description                                  | Run Script     |
| ------- | -------------------------------------------- | -------------- |
| **DR1** | Fee-only (no bonds, no slashing)             | `./run-dr1.sh` |
| **DR2** | Bonds + reputation (5% bond, 1.5× slash)     | `./run-dr2.sh` |
| **DR3** | Full system (10% bond, progressive slashing) | `./run-dr3.sh` |

Most users should start with DR3 (`./run-dr3.sh`) - the fully decentralized system.

See `data/params/PHASES.md` for detailed phase comparison and feature movement guide.

### Other Scenarios

See `data/params/` directory:

- `baseline.edn` — DR3: Full system (10% bond, 2.5% fee, progressive slashing)
- `dr1-fee-only.edn` — DR1: Fee-only (0% bond, no slashing)
- `dr2-reputation.edn` — DR2: Bonds + reputation (5% bond, 1.5× slashing)
- `whale-attack.edn` — Large escrows, high malice incentive
- `cartel.edn` — Multiple resolvers colluding
- `stress/bribery.edn` — Attackers with additional bribe capital
- `stress/sybil.edn` — Many fake resolvers

Each scenario includes:

- Description
- RNG seed (for reproducibility)
- Escrow size distribution
- Strategy mix (% honest, lazy, malicious, collusive)
- Fee/bond/slashing parameters
- Trial count + parallelism

## Outputs

Each run generates:

- `run.edn` — Full params + metadata (commit, timestamp, seed)
- `summary.edn` — Aggregated statistics (means, quantiles, confidence intervals)
- `samples.csv` — Raw trial outcomes (for deeper analysis)
- `sweep.csv` — Parameter sweep results (for visualization)

Results are timestamped and gitignored.

## Example Workflow

```bash
# 1. Check baseline
clojure -M:run data/params/baseline.edn
# Outputs: results/2026-02-11_baseline/

# 2. Run parameter sweep
clojure -M:sweep data/params/stress/cartel.edn
# Outputs: results/2026-02-11_cartel_sweep/sweep.csv

# 3. Analyze in Python (optional)
# Export CSV, plot with matplotlib

# 4. Generate report
clojure -M:clerk
# Opens http://localhost:7888/report
```

## Testing

```bash
# All tests (unit + invariants + fixture suites)
./scripts/test.sh

# Unit tests only
./scripts/test.sh unit

# S01–S41 deterministic invariant scenarios only
./scripts/test.sh invariants

# Fixture suites only
./scripts/test.sh suites
```

## Reproducibility

To re-run a previous result:

```bash
# Load the run.edn from results/
(read-string (slurp "results/2026-02-11_baseline/run.edn"))
; Extract seed, params, then re-run with same seed

clojure -M:run --seed <seed> --params-file <path>
```

## Development

### REPL

```bash
clojure -M:dev
```

Then in nREPL:

```clojure
(require '[resolver-sim.sim.trial :as trial])
(require '[resolver-sim.io.params :as params])

; Load params
(def baseline (data/params/load-edn "data/params/baseline.edn"))

; Run one trial
(trial/run-trial (java.util.SplittableRandom. 42) baseline)

; Run a batch
(trial/run-batch baseline 1000 42)
```

### Adding a New Model Component

1. Add `.clj` file to `src/resolver_sim/stochastic/`
2. Implement pure functions (no I/O, no randomness except via RNG arg)
3. Add tests to `test/resolver_sim/` with property-based tests
4. Export from `stochastic/types.clj` if part of public interface

## References

- [Clojure Guide](https://clojure.org/)
- [SplittableRandom (Java)](https://docs.oracle.com/javase/8/docs/api/java/util/SplittableRandom.html)
- [Clerk Documentation](https://clerk.vision/)
- [test.check (Property-Based Testing)](https://github.com/clojure/test.check)

## License

Apache 2.0 (same as Sew protocol)
