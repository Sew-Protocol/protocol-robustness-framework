# Invariant Catalog (Generated)

Source of truth: `src/resolver_sim/definitions/registry.clj` (`invariants`).

Definitions hash: `1082033428`

| Invariant ID | Label | Default Severity | Class | Related Transitions | Related Scenario Families | Artifact Field(s) |
|---|---|---|---|---|---|---|
| `conservation` | Conservation | `high` | `safety` | `create_escrow`, `release`, `execute_resolution`, `execute_pending_settlement` | `scenario-deep-dive`, `economic-solvency` | `metrics.invariant-results`, `metrics.invariant-violations` |
| `finality` | Finality | `medium` | `liveness` | `release`, `execute_resolution`, `execute_pending_settlement`, `automate_timed_actions` | `scenario-deep-dive`, `deadline-boundary` | `metrics.invariant-results`, `metrics.invariant-violations` |
| `solvency` | Solvency | `high` | `safety` | `create_escrow`, `release`, `execute_resolution`, `execute_pending_settlement`, `automate_timed_actions` | `economic-solvency`, `threat-detected` | `metrics.invariant-results`, `metrics.invariant-violations` |

## Interpretation

- **Failure meaning:** a failed invariant indicates a protocol property violation in simulation outputs.
- **Related transitions/scenario families:** sourced from `definitions.registry/invariant-metadata`.
- **Artifact fields:** current replay/test artifacts expose aggregate and per-invariant outcome fields under metrics.
