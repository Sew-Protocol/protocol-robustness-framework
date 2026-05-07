# Python Simulation Tools

This directory contains the Python-side validation and tooling that interacts with the SEW simulation engine via gRPC.

**Scope:** adversarial strategy generation + visual artifact rendering. All protocol simulation logic lives in Clojure/Babashka. Python handles gRPC client interactions and diagram output.

## Package: `sew_sim/`

Core Python package for interfacing with the gRPC simulation server.

- `grpc_client.py`: gRPC client for the Clojure simulation server.
- `simulation_pb2.py` / `simulation_pb2_grpc.py`: Auto-generated protobuf stubs.
- `agents.py`: Agent role definitions.
- `adversarial.py`: Adversarial strategy primitives.
- `live_agents.py` / `live_runner.py`: Live multi-agent runner.
- `anvil_runner.py`: Anvil-backed EVM trace runner.

## Primary Scripts

- `invariant_suite.py`: Adversarial suite (S24–S35). Connects to the gRPC server to replay failure-mode traces and check invariant satisfaction.
- `adversarial_agent.py`: Parametric adversarial agent with guided/random strategy mix.
- `adversarial_profitability_sweep.py`: Sweeps fee/latency/capacity parameter space; emits `results/profitability-surfaces/*/surface.csv`.
- `attack_outcome_map.py`: Generates Mermaid visual diagrams from trace files (state diagram, escalation timeline, profitability snapshot). Run via `bb report:attack-map`.
- `trace_compare.py`: Post-processes JSON trace outputs into comparison reports. Run via `bb trace:compare`.
- `eth_failure_modes.py` / `eth_failure_modes_2.py`: Scenario modules for S24–S28 and S29–S33 respectively.
- `phase_k_robustness.py` / `phase_l_optimistic.py`: Scenario modules for S34 and S35.
- `resolver_withdrawal_adversarial.py`: Rational withdrawal adversarial strategy.
- `withdrawal_attack.py` / `withdrawal_campaign.py`: Withdrawal attack runners.
- `property_tests.py`: Hypothesis-based property testing over the protocol interface.
- `demo_live.py`: Live demo runner for interactive exploration.

## Running the Adversarial Suite

```bash
# Start the gRPC simulation server
nohup clojure -M:run -- -S --port 7070 > grpc-server.log 2>&1 &
sleep 10

# Run all adversarial scenarios (S24–S35)
python3 invariant_suite.py

# Run a single scenario
python3 invariant_suite.py --scenario F3

# Generate visual artifacts from traces
bb report:attack-map
```

## Development Artifacts

`test_*.py` and `debug_*.py` files in this directory are development/debugging scripts and are not part of the documented test suite. The canonical test entrypoint is `invariant_suite.py`.
