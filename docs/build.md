# Build reference

## JAR Variants

| JAR | Contents | Entry Point | Use Case |
|-----|----------|-------------|---------|
| `prf-runner-core` | Core framework | `resolver-sim.replay-core` | Standalone replay verification |
| `prf-runner-sew` | Core + Sew protocol | `resolver-sim.minimal-runner` | Sew scenario replay |
| `prf-benchmark` | Core + Sew + benchmarks + concepts + scenarios + suites + config | `resolver-sim.benchmark.cli` | Benchmark execution |

## Building

```bash
clojure -T:build uberjar :variant core
clojure -T:build uberjar :variant sew
clojure -T:build uberjar :variant benchmark
```

Output goes to `target/prf-runner-<variant>-<version>[-uber].jar`.

## Portable Usage

The `prf-benchmark.jar` includes all reference data as classpath resources.
It runs from any directory and does **not** require:
- A git repository
- Source code checkout
- Scenario/benchmark/concept files on the filesystem

```bash
# List available benchmarks (works anywhere)
java -jar prf-benchmark.jar --list

# Run a benchmark by ID
java -jar prf-benchmark.jar run-benchmark escrow-dispute-v1

# Run a benchmark by ID with output path
java -jar prf-benchmark.jar run-benchmark --output ./results/evidence.edn escrow-dispute-v1

# Run with a specific manifest file
java -jar prf-benchmark.jar benchmarks/packs/sew/escrow-dispute-v1.edn

# Legacy invocation (no subcommand)
java -jar prf-benchmark.jar escrow-dispute-v1
```

### External/experimental concepts and packs

Use `--bundle` or pass explicit file paths for external concepts:

```bash
java -jar prf-benchmark.jar run-benchmark --bundle ./my-concepts.edn my-experimental-pack.edn
```

## Resource path scheme

All internal defaults use `resource:` URIs for embedded classpath data:

- `resource:benchmarks/registry.edn` — benchmark pack registry
- `resource:benchmarks/scoring/*.edn` — scoring rule definitions
- `resource:benchmarks/concepts/*.edn` — benchmark-local concepts
- `resource:data/concepts/registry.edn` — global concept registry
- `resource:scenarios/edn/` — executable scenario files
- `resource:suites/reference-validation-v1/manifest.edn` — reference validation suite
- `resource:config/evidence.json` — evidence chain configuration

External paths use `file:` prefix or bare filesystem paths. The resolution
order for bare paths is: filesystem first, classpath second.

## Exit codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Generic error or benchmark failure |
| 2 | Unknown benchmark ID |
| 3 | Missing concept reference |
| 4 | Missing scenario file |
| 5 | Invalid parameters |
| 6 | Duplicate conflicting registries |
