# Evidence Artifact Contract (Generated)

Source of truth: current emitted artifacts under `results/test-artifacts`, CDRS schemas under `spec/`, and semantic registry hash.

Definitions hash: `1471206304`

## Canonical Artifact Set

- `results/test-artifacts/test-summary.json`
- `results/test-artifacts/coverage.json`
- `results/test-artifacts/findings.json`
- `results/test-artifacts/issues.json`

## Required Provenance Fields (run-level)

- `run_id` / `run.id`
- `git_sha` where available
- `definitions/hash` or derived semantic hash alignment
- generation timestamp

## Top-level Contract: test-summary.json

| Key |
|---|
| `acceptance_decision` |
| `failure_count` |
| `force_refund_forward_only` |
| `mode` |
| `overall_status` |
| `phase_failures` |
| `risk_digest` |
| `run_id` |
| `status_counts` |
| `targets` |

## Top-level Contract: coverage.json

| Key |
|---|
| `by-purpose` |
| `by-threat-tag` |
| `canonical-transitions` |
| `guard-by-purpose-hit-freq` |
| `guard-by-threat-tag-hit-freq` |
| `guard-hit-freq` |
| `scanned-dir` |
| `scenarios` |
| `schema-versions` |
| `threat-tag-freq` |
| `total` |
| `transition-by-purpose-hit-freq` |
| `transition-by-threat-tag-hit-freq` |
| `transition-hit-freq` |
| `transition-outcome-freq` |
| `unclassified-count` |
| `unhit-transitions` |

## Top-level Contract: findings.json

| Key |
|---|
| `findings` |
| `overall_status` |
| `provenance` |
| `run` |
| `schema_version` |
| `story_candidates` |

## Top-level Contract: issues.json

| Key |
|---|
| `generated-at` |
| `id` |
| `issue-count` |
| `issues` |
| `policy` |
| `version` |

## CDRS Trace/Event Required Fields

### cdrs-trace-v0.2 required

- `cdrs_version
- `schema_version
- `scenario_id
- `trace_kind
- `steps

### cdrs-event-v0.2 required

- `cdrs_version
- `event_type
- `step_type
- `context_id
- `actor
- `timestamp
- `state_bucket

## Validation Notes

- Contract is additive: unknown fields may exist, but listed top-level keys are expected for current generated artifacts.
- CI drift checks enforce this document is regenerated whenever artifact structures change.
- External verifiers should check run id, git sha, and definitions hash coherence before trusting claims.
