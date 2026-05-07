# Running Simulations & Tests

## Quick Start

### Canonical test entrypoint (recommended)
```bash
./scripts/test.sh all
```
This is the authoritative repository-wide validation command.

It runs five targets in sequence:
- `unit` → Clojure unit tests
- `generators` → deterministic generator/property regression checks
- `contracts` → cross-layer contract checks (`proto/simulation.proto` ↔ gRPC server ↔ Python client)
- `invariants` → deterministic scenario run (`--invariants`, S01–S41)
- `suites` → fixture suites (`all-invariants`, `equilibrium-validation`, `spe-validation`)

### Run comprehensive suite with full reporting
```bash
./run.sh all
```
Use this for report generation workflows; use `scripts/test.sh` as the canonical validation gate.

### Run specific canonical targets
```bash
./scripts/test.sh unit
./scripts/test.sh generators
./scripts/test.sh contracts
./scripts/test.sh invariants
./scripts/test.sh suites
./scripts/test.sh triage
```

### Machine-readable CI artifacts

When running `./scripts/test.sh all`, the script writes a JSON summary:

```text
results/test-artifacts/test-summary.json
```

It includes per-target status, exit codes, durations, and log file paths.

### Baseline vs scenario comparison (research-shareable)

Use this to generate a compact, researcher-facing diff between two replay outputs.

```bash
bb trace:compare \
  --baseline results/baseline.trace.json \
  --candidate results/candidate.trace.json \
  --out-dir results/trace-compare/example
```

Outputs:
- `results/trace-compare/example/comparison.json`
- `results/trace-compare/example/comparison.md`

The report includes:
- outcome + events processed
- key metric deltas
- terminal-state count differences
- a single headline line you can paste into research notes

### Attack-to-outcome visual map (shareable diagrams)

Generate three canonical visual artifacts from fixtures/sweep outputs:

```bash
bb report:attack-map --out-dir results/attack-outcome-map
```

Outputs (timestamped directory):
- `state-transition-heatmap.md` — action→action transition heatmap table
- `profitability-surface-snapshot.md` — top risk points from latest `adv:sweep` surface
- `escalation-timeline.md` — Mermaid escalation/settlement timeline

If no profitability surface exists yet, run:

```bash
bb adv:sweep
```

### Transition/guard coverage release gate

```bash
bb report:release-gate
```

Prints:
- transition hit frequencies
- guard hit frequencies
- purpose-grouped hit maps
- explicit **unhit transition backlog** for release-candidate closure

### Adversarial profitability surfaces (rational-agent coverage)

```bash
bb adv:sweep
```

Outputs:
- `results/profitability-surfaces/<timestamp>/surface.csv`
- `results/profitability-surfaces/<timestamp>/surface.json`
- `results/profitability-surfaces/<timestamp>/regions.json`
- `results/profitability-surfaces/<timestamp>/promotions.json`

For expanded promotion backlog:

```bash
bb adv:sweep:promote
```

### Run specific phase
```bash
./run.sh phase-i      # Phase I only (1D + 2D sweeps)
./run.sh phase-j      # Phase J only (4 scenarios)
./run.sh baseline     # Baseline scenario only
```

---

## Individual Simulations

### Baseline (control scenario)
```bash
clojure -M:run -- -p data/params/baseline.edn
```
Expected output:
- Honest profit: 150.00
- Malice profit: 150.00
- Dominance: 1.0 (neutral)

### Phase I: Detection Mechanisms (1D sweep)
```bash
clojure -M:run -- -p data/params/phase-i-all-mechanisms.edn -s
```
Expected output:
- All strategies pass
- Malice profit: -199.60 (deeply unprofitable)

### Phase I: 2D Sensitivity Sweep
```bash
clojure -M:run -- -p data/params/phase-i-2d-all-mechanisms.edn -s
```
Sweeps detection vs slash multiplier combinations.

### Phase H: Realistic Bond Mechanics
```bash
clojure -M:run -- -p data/params/phase-h-realistic-mechanics.edn
```
Expected output:
- Escape: BLOCKED (freeze + unstaking + appeal)
- Bond security: PROVEN

### Phase G: 2D Parameter Sweep
```bash
clojure -M:run -- -p data/params/phase-g-sensitivity-2d.edn -s
```
Identifies break-even point: 10% detection + 2.5× slash

### Phase J: Multi-Epoch Reputation

```bash
# Baseline (control - no detection decay)
clojure -M:run -- -p data/params/phase-j-baseline-stable.edn -m

# Governance decay (50% detection loss per epoch)
clojure -M:run -- -p data/params/phase-j-governance-decay.edn -m

# Governance failure (detection → 0 at epoch 5)
clojure -M:run -- -p data/params/phase-j-governance-failure.edn -m

# Sybil re-entry test
clojure -M:run -- -p data/params/phase-j-sybil-re-entry.edn -m
```

Expected Phase J output (all scenarios):
- 10 epochs executed
- Honest cumulative profit: 1500
- Malice cumulative profit: 1200-1400
- Reputation prevents profitable exit/re-entry

---

## Output & Results

### Results Directory
All results saved to `results/` with timestamp:
```
results/
├── 2026-02-12_15-45-08/
│   ├── COMPREHENSIVE_REPORT.md      # Main report
│   ├── 01-baseline.log              # Test logs
│   ├── 02-phase-i-1d.log
│   ├── 2026-02-12_15-45-11_baseline-v1/    # Simulation outputs
│   │   ├── summary.edn              # Raw results
│   │   ├── metadata.edn             # Test metadata
│   │   └── results.csv              # Profit distribution (if applicable)
│   └── ...
```

### Understanding Results

**Key metrics by phase:**

| Phase | Key Metric | Interpretation |
|-------|-----------|-----------------|
| Baseline | Dominance ratio | Should be 1.0 (neutral) |
| Phase I | Malice profit | Should be negative (< -100) |
| Phase H | Escape-count | Should be 0 (impossible) |
| Phase G | Break-even | ~10% detection, 2.5× slash |
| Phase J | Honest vs Malice | Grows ~13× difference (1500 vs ~1300) |

---

## Troubleshooting

### "Could not find artifact io.github.nextjournal:clerk"
Clerk report generation not available (network/dependency issue).
- This is optional - simulation results still saved to markdown
- Reports still generated without Clerk

### Clojure command not found
Install Clojure:
```bash
# macOS
brew install clojure

# Linux (download script)
curl -O https://download.clojure.org/install/linux-install-1.11.0.sh
chmod +x linux-install-1.11.0.sh
sudo ./linux-install-1.11.0.sh
```

### Simulations hang or crash
Check system resources:
```bash
# Verify Clojure is running
ps aux | grep clojure

# Check available memory
free -h

# Try with smaller parameter set
clojure -M:run -p data/params/baseline.edn
```

---

## CLI Reference

```
Usage: clojure -M:run [options]

Options:
  -p, --params PATH  data/params/baseline.edn  Path to params.edn file
  -o, --output DIR   results              Output directory for results
  -s, --sweep                             Run strategy sweep
  -m, --multi-epoch                       Run Phase J multi-epoch simulation
  -h, --help                              Show this help
```

**Important**: Use `--` before arguments when using wrapper scripts:
```bash
clojure -M:run -- -p data/params/phase-i.edn -s
#                 ^^^ Required separator
```

---

## System Requirements

- **Clojure**: 1.12.0+
- **JVM**: 11+
- **Memory**: 2GB minimum, 4GB recommended
- **CPU**: Multi-core (parallel trials)
- **Time**: 30s baseline → 2min per phase

---

## Phase J CLI Integration (NEW)

Phase J multi-epoch simulation now integrated into main CLI:

```bash
# Run baseline (stable) scenario
clojure -M:run -- -p data/params/phase-j-baseline-stable.edn -m

# Run governance failure test
clojure -M:run -- -p data/params/phase-j-governance-failure.edn -m -o my-results/
```

Output includes:
- Per-epoch metrics (honest, malice, dominance ratio)
- Resolver exit tracking
- Cumulative profit by strategy
- Win rate statistics
- Multi-epoch aggregated stats

---

## For CI/CD Integration

```bash
#!/bin/bash
# Example CI job

set -e  # Exit on error

echo "Running canonical validation gate..."
./scripts/test.sh all || exit 1

echo "Running comprehensive suite..."
./run.sh all

echo "Generating report..."
# Report automatically created in results/*/COMPREHENSIVE_REPORT.md

# Optional: upload results
# aws s3 cp results/ s3://bucket/sew-simulation/
```

---

See `PHASE_J_INTEGRATION_COMPLETE.md` for detailed findings and bug analysis.
