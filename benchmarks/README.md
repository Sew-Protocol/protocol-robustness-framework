# Benchmarks & Evidence Generation

This layer provides deterministic benchmark execution and evidence generation for protocol robustness validation.

## Architecture

- **Manifests:** Defined in `benchmarks/*.edn`. They specify scenario suites, invariants, and metrics.
- **Runner:** Executes suites via a `RepositoryAdapter` and collects results.
- **Evidence Bundles:** Portable EDN artifacts containing repo metadata, environment info, and execution traces.
- **Determinism:** Evidence bundles are hashed using canonical EDN serialization and SHA-256. Identical inputs (repo state + benchmark) produce identical hashes.
- **Signing:** Users can sign evidence hashes locally using Ed25519 (optional).

## Usage

### Run a benchmark
```bash
bb benchmark:run benchmarks/dispute-liveness.edn
```

### Run and sign
```bash
bb benchmark:run benchmarks/dispute-liveness.edn -k ~/.ssh/id_ed25519
```

### Verify evidence bundle
```bash
bb benchmark:verify evidence/latest.edn
```

### View evidence hash
```bash
bb benchmark:hash evidence/latest.edn
```

## Repository Adapter

The `RepositoryAdapter` protocol allows the runner to be protocol-agnostic. The default `SewAdapter` handles SEW-v1 scenarios (JSON/EDN).

```clojure
(defprotocol RepositoryAdapter
  (load-scenarios [this benchmark])
  (execute-benchmark [this benchmark scenarios])
  (collect-metrics [this results]))
```
