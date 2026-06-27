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
    stablecoin-safety/          Stablecoin safety benchmarks

  scoring/                      Scoring rule definitions
    severity_weighted_v1.edn
    binary_claims_v1.edn

  runners/                      Runner policy configurations
    local.edn
    with-sew.edn
    quorum-local-v0.edn

  scenarios/                    Scenario suite references

  outputs/                      Generated benchmark output (gitignored)

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
| claims                    | Atomic properties under test    |

## Key Boundaries

**PRF** owns the benchmark infrastructure: registry, scenario selection,
runner selection, canonical result format, evidence policy, scoring
rules, artifact emission, runner attestations, and consensus.

**Sew** (and other protocols) are benchmark subjects — they provide
the protocol-specific scenarios, claims, and adapters that PRF runs
through its infrastructure.
