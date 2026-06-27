# Quick Start: Protocol Robustness Framework

## Requirements

- **Java 11+** / **Clojure CLI** ([install guide](https://clojure.org/guides/install_clojure))
- **Python 3.10+** (adversarial bridge only — not needed for in-process runs)

---

## Step 1: Run the canonical validation gate

This is the authoritative single-command check. Run it first.

```bash
./scripts/test.sh all
```

Runs five targets in sequence: unit tests, generator regression, contract checks,
deterministic invariant suite (S01–S100), and fixture suites. See
`docs/testing/RUNNING_TESTS.md` for the current known-baseline and any expected failures.

---

## Step 2: Run the deterministic invariant suite alone (fast)

No server required. Runs in ~1 second.

```bash
clojure -M:run -- --invariants
```

Executes all S01–S100 in-process scenarios against the Sew state machine and checks
invariants at every transition. Each scenario is pass/fail with explicit violation
counts. Expect output like:

```
  Sew Invariant Suite — Deterministic Scenarios
  ✓ PASS  S01  baseline-happy-path          steps=3   reverts=0
  ✓ PASS  S02  dr3-dispute-release          steps=4   reverts=0
  ...
  82/99 passed  (0.8 s)
```

---

## Step 3: Run the adversarial gRPC suite

Requires the Clojure gRPC server.

```bash
# Start server in background
nohup clojure -M:run -- -S --port 7070 > grpc-server.log 2>&1 &
sleep 8

# Run all adversarial scenarios (Python)
pip install -e python/
cd integration/python && python invariant_suite.py
```

This runs 33 adversarial scenarios where Python agents interact with the live
contract model. See `docs/usage.md` for scenario-by-scenario flags.

---

## Step 4: Run a Monte Carlo statistical phase

No server required. Results written to `results/`.

```bash
clojure -M:run -- -p data/params/baseline.edn
```

See `data/params/PHASES.md` for the full list of parameter files and what each
phase hypothesis tests.

---

## Architecture Overview

```
src/resolver_sim/
  contract_model/   — Protocol-agnostic deterministic replay kernel
  protocols/        — SimulationAdapter interfaces + Sew and Dummy adapters
    sew/            — Sew state machine, lifecycle, accounting, invariants
  stochastic/       — Statistical models (rng, economics, decision quality)
  sim/              — Monte Carlo simulation phases and harness
  io/               — Parameter loading and result serialization
  core.clj          — CLI entry point
```

All logic in `protocols/`, `contract_model/`, and `stochastic/` is pure (no side
effects). RNG is an explicit parameter. See `docs/architecture/ARCHITECTURE.md`
for the full namespace map and layering rules.

---

## Reproducibility

**Same seed + params → identical output, byte-for-byte.**

1. Deterministic replay: no randomness. Same scenario + same code = same trace.
2. Monte Carlo: explicit SplittableRandom seed. Same seed + params = identical results.
3. Every run captures git SHA, seed, JVM version, and timestamp in `metadata.edn`.

---

## Troubleshooting

**`./scripts/test.sh` fails with reader or namespace errors**
Check `docs/testing/RUNNING_TESTS.md` for the current known-baseline. Some
failures are tracked and documented; confirm whether yours is known before debugging.

**"No such file or directory"**
Run scripts from the repository root. Paths start with `data/params/` or `data/fixtures/`.

**gRPC server not starting**
Check `grpc-server.log` for errors. Allow 10–15 seconds for JVM startup.
Verify port 7070 is not already in use.

**Permission denied on scripts**
```bash
chmod +x run.sh scripts/test.sh
```

