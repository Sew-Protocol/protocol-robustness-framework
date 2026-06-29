# Benchmarks

Canonical benchmark root. Protocol-agnostic benchmark infrastructure
lives here; protocol-specific packs appear under `packs/<protocol>/`.

## Directory Layout

```
benchmarks/
  README.md
  BENCHMARK_PACK_SPEC_V1.md
  BENCHMARK_RESULT_SPEC_V1.md

  registry.edn                  Global pack + domain index

  packs/                        Protocol/domain benchmark packs
    prf-core/                   Core PRF infrastructure benchmarks
    sew/                        Sew protocol benchmarks

  concepts/                     Benchmark-specific concept definitions
    protocol-robustness-v0.edn  Concepts for the protocol-robustness-v0 pack

  scoring/                      Scoring rule definitions
    severity-weighted-v1.edn
    binary-claims-v1.edn
    robustness-dimensions-v0.edn

  archived/                     Legacy/experimental material
```

## Benchmark as Contract

Each benchmark is a registered evaluation contract — a bundle of
references that specifies:

| Field                     | Reference                      |
|---------------------------|--------------------------------|
| domain                    | Problem domain under test       |
| protocol                  | Protocol being benchmarked      |
| scenario suite            | Set of scenarios to execute     |
| runner policy             | Execution parameters             |
| evidence policy           | Evidence collection + hashing   |
| scoring rule              | Pass/fail/mixed classification  |
| concepts                  | Stakeholder-facing explanations  |
| claims                    | Atomic properties under test    |

## Concepts Layer

Benchmark-specific concept definitions live under `concepts/`. These
map low-level scenario mechanics, claims, and evidence types to
stakeholder-readable language. Unlike the reusable use-case and
decision-quality concepts in `data/concepts/`, benchmark concepts
are specific to what a given benchmark evaluates.

Each concept entry includes:
- `:concept/title` and `:concept/summary` — what the concept means
- `:concept/stakeholder-language` — plain-language explanation
- `:concept/maps-to` — references to scenarios, claims, invariants, evidence
- `:concept/why-it-matters` — why this property matters to users

## Key Boundaries

**PRF** owns the benchmark infrastructure: registry, scenario selection,
runner selection, canonical result format, evidence policy, scoring
rules, artifact emission, runner attestations, and consensus.

**Sew** (and other protocols) are benchmark subjects — they provide
the protocol-specific scenarios, claims, and adapters that PRF runs
through its infrastructure.

## Running a Benchmark

```bash
# List available benchmarks
bb benchmark:list

# Run a benchmark by ID
bb benchmark:run :benchmark/prf-protocol-robustness-v0

# Reproduce from an evidence bundle
bb benchmark:reproduce <evidence-path>
```
