# Benchmark Scenarios

Scenario suites referenced by benchmark definitions. This directory
classifies existing scenario material without duplicating it.

## Suite Reference

| Suite Keyword                           | Source Directory                      |
|-----------------------------------------|---------------------------------------|
| `:suite/sew-dispute-safety-v1`          | `suites/reference-validation-v1/`     |
| `:suite/sew-yield-safety-v1`            | `suites/yield-reference-v1/`          |
| `:suite/prf-replay-v1`                  | `suites/reference-validation-v1/`     |
| `:suite/reference-validation-v1`        | `suites/reference-validation-v1/`     |
| `:suite/sew-shortfall-allocation-v0`    | (resolved via suites.clj)             |
| `:suite/prf-evidence-v1`                | (not yet materialized)                |
| `:suite/stablecoin-transfer-v0`         | (not yet materialized)                |
| `:suite/stablecoin-redemption-v0`       | (not yet materialized)                |

## Adding Scenarios

New scenario suites for benchmarks must be registered in
`src/resolver_sim/scenario/suites.clj` under `pack-suites`.
This is required for benchmark pack validation to resolve
scenario IDs against the referenced suite.
