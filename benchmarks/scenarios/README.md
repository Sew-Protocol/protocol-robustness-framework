# Benchmark Scenarios

Scenario suites referenced by benchmark definitions. This directory
classifies existing scenario material without duplicating it.

## Suite Reference

| Suite Keyword                  | Source Directory                      |
|-------------------------------|---------------------------------------|
| `:suite/sew-dispute-safety-v1` | `suites/reference-validation-v1/`     |
| `:suite/sew-yield-safety-v1`   | `suites/yield-reference-v1/`          |
| `:suite/sew-domain-v1`         | `suites/sew-domain-reference-v1/`     |
| `:suite/prf-replay-v1`         | `suites/reference-validation-v1/`     |
| `:suite/prf-evidence-v1`       | (not yet materialized)                |
| `:suite/stablecoin-transfer-v0` | (not yet materialized)              |
| `:suite/stablecoin-redemption-v0` | (not yet materialized)            |

## Adding Scenarios

New scenario suites should be placed under `scenarios/<domain>/`
with a descriptive name. Register them in `benchmarks/registry.edn`
under `:scenario-suites`.
