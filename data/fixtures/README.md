# Sew Simulation Fixtures

This directory contains deterministic fixtures used for protocol validation and regression testing.

## Directory Structure

- `protocol/`: Protocol parameters (`ModuleSnapshot`).
- `states/`: Initial world states (stakes, existing escrows).
- `actors/`: Behavioral profiles for agents.
- `authority/`: Governance and Oracle oversight parameters.
- `tokens/`: Token behavior profiles (decimals, fee-on-transfer).
- `traces/`: Replayable event sequences (`.trace.json`).
- `thresholds/`: Data-driven invariant acceptance criteria.
- `suites/`: Canonical entry points that compose the above fixtures.
- `golden/`: (Generated) Reference execution reports for drift detection.

## Suite Schema

A suite EDN file defines the composition of a test scenario:

```clojure
{:suite/id :suites/my-test
 :suite/title "Test Title"
 :suite/purpose "What this tests"
 :suite/class :governance | :ordering | :economic
 
 :protocol :protocol/baseline
 :state    :states/minimal-world
 :traces   [:traces/some-scenario]}
```

## Running Suites

Use the Clojure runner:

```bash
clojure -M -e "(require '[resolver-sim.sim.fixtures :as f]) (f/run-suite :suites/my-test)"
```
