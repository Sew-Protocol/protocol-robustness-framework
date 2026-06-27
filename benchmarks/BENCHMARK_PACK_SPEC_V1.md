# Benchmark Pack Spec V1

## Registry Format

### Global Registry (`benchmarks/registry.edn`)

```clojure
{:registry/spec :spec/benchmark-registry-v1
 :registry/version 1
 :domains [{:domain/id <qualified-kw>
            :domain/description <string>} ...]
 :packs   [{:pack/id <qualified-kw>
            :pack/description <string>
            :pack/registry <path>} ...]}
```

### Pack Registry (`benchmarks/packs/<protocol>/registry.edn`)

```clojure
{:registry/spec :spec/pack-registry-v1
 :registry/version 1
 :pack/id <qualified-kw>
 :pack/description <string>
 :pack/domain <qualified-kw>
 :benchmarks [{:benchmark/id <qualified-kw>
               :benchmark/domain <qualified-kw>
               :benchmark/file <path>
               :benchmark/status <:active|:deprecated|:experimental>} ...]}
```

## Benchmark Definition (Bundle Format)

A benchmark is a bundle of references — an evaluation contract:

```clojure
{:benchmark/id              <qualified-kw>   ;; globally unique
 :benchmark/domain          <qualified-kw>   ;; domain reference
 :benchmark/protocol        <qualified-kw>   ;; protocol reference
 :benchmark/scenario-suite  <qualified-kw>   ;; scenario suite reference
 :benchmark/runner-policy   <qualified-kw>   ;; runner policy reference
 :benchmark/evidence-policy <qualified-kw>   ;; evidence policy reference
 :benchmark/scoring-rule    <qualified-kw>   ;; scoring rule reference
 :benchmark/claims          [<qualified-kw> ...]}  ;; claims under test
```

All references resolve through the registry or named definitions under
`scenarios/`, `runners/`, `scoring/`, etc.

## Claims

Claims are the atomic units of benchmark evaluation. Each claim
encodes a property the protocol must satisfy. Scoring rules interpret
claim outcomes to produce a benchmark result.

Claim definitions live in `benchmarks/packs/<protocol>/claims/` or are
referenced by keyword from the benchmark definition.

## Runner Policies

Runner policies specify execution parameters:

| Field                        | Description                          |
|------------------------------|--------------------------------------|
| `:runner-policy/id`          | Qualified keyword identifier          |
| `:runner-policy/adapter`     | RepositoryAdapter implementation      |
| `:runner-policy/concurrency` | Thread pool size                     |
| `:runner-policy/output`      | Output directory                     |
| `:runner-policy/consensus-mode` | `:single-run` or `:multi-run`     |

## Scoring Rules

Scoring rules define how claim outcomes are reduced to a score:

| Field                | Description                           |
|----------------------|---------------------------------------|
| `:scoring/id`        | Qualified keyword identifier           |
| `:scoring/rules`     | Classifier, pass/fail/mixed thresholds |
| `:scoring/description` | Human-readable explanation           |

## Evidence Policies

Evidence policies specify what evidence is collected and how it is
hashed. The default `:evidence-policy/forensic-standard-v1` collects
all scenario traces, invariant results, and environment metadata,
then hashes them using canonical EDN serialization + SHA-256.
