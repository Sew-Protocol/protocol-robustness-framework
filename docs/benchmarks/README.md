# Benchmark JAR

Build a self-contained `prf-benchmark.jar` that includes all reference
scenarios, concepts, scoring rules, and suite definitions as embedded
classpath resources. The JAR runs from any directory — no git, no
source checkout, no data files on the filesystem.

## Build

```bash
clojure -T:build uberjar :variant benchmark
```

Output: `target/prf-runner-benchmark-0.1.0-uber.jar`

### Prerequisites

- Clojure CLI (`clojure` on PATH)
- Java 17+

## What's in the JAR

| Contents | Path (inside JAR) |
|----------|-------------------|
| Benchmark pack registry | `benchmarks/registry.edn` |
| Pack definitions | `benchmarks/packs/*/` |
| Scoring rules | `benchmarks/scoring/*.edn` |
| Benchmark-local concepts | `benchmarks/concepts/*.edn` |
| Global concept registry | `data/concepts/registry.edn` |
| Executable scenarios | `scenarios/edn/` |
| Reference validation suite | `suites/reference-validation-v1/` |
| Evidence config | `config/evidence.json` |

All internal paths use the `resource:` scheme and are loaded from the
classpath. No filesystem access required.

## Run

```bash
# List available benchmarks
java -jar target/prf-runner-benchmark-0.1.0-uber.jar --list

# Run the default benchmark
java -jar target/prf-runner-benchmark-0.1.0-uber.jar

# Run a specific benchmark by ID
java -jar target/prf-runner-benchmark-0.1.0-uber.jar run-and-report escrow-dispute-v1

# Run with explicit output path
java -jar target/prf-runner-benchmark-0.1.0-uber.jar run-and-report --output ./out/evidence.edn escrow-dispute-v1

# Sign the evidence with a key
java -jar target/prf-runner-benchmark-0.1.0-uber.jar run-and-report -k ./signing-key.pem escrow-dispute-v1
```

### Exit codes

| Code | Meaning |
|------|---------|
| 0 | All scenarios passed |
| 1 | Benchmark failed or generic error |
| 2 | Unknown benchmark ID |

## Add a custom benchmark pack

1. Create a pack directory: `benchmarks/packs/<name>/`
2. Add a registry file: `benchmarks/packs/<name>/registry.edn`
3. Register it in `benchmarks/registry.edn` under `:packs`
4. Rebuild the JAR

See `BENCHMARK_PACK_SPEC_V1.md` for the pack registry schema.

## Directory layout

```
benchmarks/
├── registry.edn              # Top-level pack index
├── packs/
│   └── sew/                  # Pack directory
│       ├── registry.edn      #   Pack registry
│       └── escrow-dispute-v1.edn  #   Benchmark manifest
├── scoring/                  # Scoring rule definitions
├── concepts/                 # Benchmark-local concepts
└── README.md                 # This file
```
