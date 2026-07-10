# Usage Guide

## Requirements

- **Java 11+** (for Clojure)
- **Clojure CLI** — [install guide](https://clojure.org/guides/install_clojure)
- **Python 3.10+**

---

## Layer 1: Adversarial Invariant Suite

### Start the gRPC server

The adversarial suite requires the Clojure gRPC server running on port 7070.

```bash
# From repo root — starts server in background
nohup clojure -M:run -- -S --port 7070 > grpc-server.log 2>&1 &
sleep 8   # wait for JVM startup

# Verify it's running
grep "gRPC server started" grpc-server.log
```

### Install Python dependencies

```bash
pip install -e python/
```

### Run all 33 scenarios

```bash
cd integration/python
python invariant_suite.py
```

Expected: `33/33 scenarios passed` in ~0.6–2 seconds.

### Run a single scenario

```bash
# By scenario ID (F-prefix or S-prefix)
python invariant_suite.py --scenario F3
python invariant_suite.py --scenario S08

# Scenario IDs are case-insensitive
python invariant_suite.py --scenario f10
```

### Write a JSON report

```bash
# Default path: results/invariant-suite-<timestamp>.json
python invariant_suite.py --json

# Custom path
python invariant_suite.py --json /tmp/report.json
```

The JSON report includes: git SHA, branch, Python version, UTC timestamp, per-scenario results (steps, reverts, attack metrics), and suite-level summary statistics.

### Understanding the output

```
  ✓ PASS  S26  f3-governance-sandwich    steps=6  attacks=4  successes=2
               rotations=1  malicious_resolutions_attempted=3  malicious_resolutions_succeeded=1
```

- `steps` — total gRPC `process-step` calls in the scenario
- `reverts` — steps that were rejected by the state machine
- `attacks` — adversarial actions attempted by attack agents
- `successes` — attack actions that landed (for scenarios where this is measured)
- Scenario-specific metrics follow on the next line

A `PASS` means the scenario's specific assertion held — which for most F-scenarios means the failure mode was **reproduced** (the attack was possible) AND **no invariant was violated**.

---

## Layer 2: Clojure Contract Model

### Run the gRPC server (foreground)

```bash
clojure -M:run -- -S --port 7070
```

### Run a single trial

```bash
clojure -M:run -- -p data/params/baseline.edn
```

### Run with XTDB persistence

Requires XTDB on localhost:5432.

```bash
clojure -M:run -- -p data/params/baseline.edn --db
```

---

## Layer 3: Monte Carlo Statistical Simulation

The Monte Carlo layer runs standalone — no gRPC server required.

### Run all phases (G–AD)

```bash
scripts/monte-carlo/test-all.sh
```

Expected: 30+ phases passing, ~5–10 minutes total.

### Run specific model configurations

```bash
# DR3 full system (bonds + escalation)
scripts/monte-carlo/run-dr3.sh

# DR1 fee-only (no bonds, no slashing)
scripts/monte-carlo/run-dr1.sh

# DR2 reputation + bonds
scripts/monte-carlo/run-dr2.sh

# Adversarial parameter search (find worst-case params for attackers)
scripts/monte-carlo/run-adversarial.sh
```

### Run a specific phase

```bash
clojure -M:run -- -p data/params/phase-g-slashing-delays.edn
clojure -M:run -- -p data/params/phase-j-baseline-stable.edn -m   # multi-epoch
clojure -M:run -- -p data/params/phase-o-baseline.edn -O           # market exit
```

### Parameter files

All EDN parameter files are in `data/params/`. Use `data/params/phase-o-baseline.edn` as the canonical template for the required schema.

### Results

Results are written to `results/` (gitignored). Use `git add -f results/` to force-track a specific result file.

---

## CI Integration

The invariant suite is designed for CI. Add to your pipeline:

```yaml
# Example GitHub Actions step
- name: Run adversarial invariant suite
  run: |
    nohup clojure -M:run -- -S --port 7070 > grpc-server.log 2>&1 &
    sleep 15
    cd integration/python && python invariant_suite.py --json results/ci-run.json
```

Exit code is 0 on full pass, 1 if any scenario fails.

---

## Reproducibility

Every run prints a reproducibility header:

```
  git:     7e98fc1  (ethereum)
  python:  3.12.10
  run at:  2026-04-15T17:13:50Z
  mode:    deterministic (no randomness; seed: n/a)
```

The simulator is fully deterministic — no randomness, no timestamps in scenario logic. The same git SHA + Python version will produce identical output on any machine.
