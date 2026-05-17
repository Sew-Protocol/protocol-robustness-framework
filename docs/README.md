# Documentation Index

Primary project framing and status live in the root `../README.md`.

This file is a quick index into the documentation set, with architecture
positioning aligned to the generalized replay kernel + protocol adapter model.

## Start here by task

- **Run canonical validation / understand current failures**
  - `testing/RUNNING_TESTS.md`
- **Understand architecture and layering boundaries**
  - `architecture/ARCHITECTURE.md` (canonical)
- **Get a conceptual system overview quickly**
  - `overview/SIMULATION_OVERVIEW.md`
- **Set up and run locally**
  - `quickstart/QUICKSTART.md`
- **Understand reusable abstractions (kernel/adapter/components)**
  - `overview/REUSABLE_COMPONENTS.md`

## Documentation status convention

- **Canonical**: primary source of truth for a topic.
- **Companion**: practical or narrowed companion to a canonical doc.
- **Archived**: historical context only; do not use for current implementation decisions.

When introducing a replacement doc, keep a short pointer in the old location so
existing links remain useful.

## Core architecture docs

- `overview/SIMULATION_OVERVIEW.md` — conceptual model
- `architecture/ARCHITECTURE.md` — **canonical** technical architecture, layering, boundaries
- `architecture.md` — companion architecture narrative (diagram-oriented)
- `testing/RUNNING_TESTS.md` — canonical test entrypoints + current baseline/known failures
- `overview/REUSABLE_COMPONENTS.md` — adapter/harness/fixture reuse guide
- `quickstart/QUICKSTART.md` — setup and execution
- `testing/TEST_SUITE.md` — suite coverage and structure
- `scenarios.md` — scenario index and protocol properties
- `trace-end-equilibrium-validation.md` — trace-end game-theoretic validation boundaries
- `security-model.md` — threat model assumptions
- `usage.md` — CLI/API usage notes

## Research Outputs

- `RESEARCH_NOTE_V0.md` — 6-page research note: headline claim, model, results, limitations, open questions
- `evidence/RESEARCHER_EVIDENCE_PACK.md` — ≤15-minute reproducibility pack with three failure-class scenario pairs
- `evidence/` — per-failure-class evidence with raw JSON traces
- `challenge/BENCHMARK_CHALLENGE.md` — "Break This Mechanism" benchmark: 3 adversarial tasks with scoring

Historical planning docs are kept for context (for example
`archived/docs/roadmap-generalisation.md`).
