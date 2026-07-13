# Phase 1 — Audit and Contract Decision

## 1. Registry Producers

| Producer | File | Version Emitted | Structural Validation |
|---|---|---|---|
| Primary v1.2 producer | `scripts/evidence/write_scenario_run_manifest.py` | `cfg.schema("test-artifacts")` → `"test-artifacts.v1.2"` | Before write |
| Post-processing updater | `scripts/evidence/extract_scenario_artifacts.py` | Reads existing, appends silently | Before append |
| Legacy v1 producer | `scripts/evidence/write_test_summary.py` | **Hardcoded** `"test-artifacts.v1.2"` (line 441) | Via validator |

## 2. Registry Loaders / Consumers

| Consumer | File | Fields Read |
|---|---|---|
| Manifest loader (Clojure) | `src/resolver_sim/manifest/run.clj` | `:artifacts[].{:id :path}`, `:run-id` |
| Semantic validator (Clojure) | `src/resolver_sim/validation/integration/artifact_registry.clj` | `:artifacts`, `:verifies_against`, `:schema_version`, `:run-id` |
| Structural validator (Python) | `scripts/validate/validate_artifact_registry.py` | `schema_version`, `run_id`, `run_manifest`, `artifacts[].{id,path,sha256,schema_version,input_versions}` |
| Integrity checker (Python) | `scripts/validate/verify_artifact_registry.py` | `artifacts[].{path,sha256,bytes}` |
| Bundle verifier (Python) | `scripts/validate/verify_evidence_bundle.py` | `artifacts[].{id,path,sha256}` |

## 3. Validators

| Validator | File | Scope |
|---|---|---|
| Python artifact-registry validator | `scripts/validate/validate_artifact_registry.py` | Schema version, file existence, SHA256, run_id cross-ref, claimable integrity |
| Python artifact integrity checker | `scripts/validate/verify_artifact_registry.py` | SHA256 + byte size per artifact |
| Python bundle verifier | `scripts/validate/verify_evidence_bundle.py` | Registry SHA256, envelope binding, Ed25519 signature |
| Clojure integration validator | `src/resolver_sim/validation/integration/artifact_registry.clj` | Artifacts-present, dangling deps, ambiguous schema versions |
| Clojure adapter | `src/resolver_sim/validation/adapters/artifact_registry.clj` | Translates check maps → validation-root.v1 |

## 4. Fixtures

| Fixture | File | Version Used |
|---|---|---|
| `all-pass-registry` | `test/resolver_sim/validation/integration/artifact_registry_test.clj:9` | No `schema_version` field (minimal test shape, legacy `:verifies_against`) |
| `broken-registry` | Same file:22 | No `schema_version` field (legacy `:verifies_against` only) |
| `realistic-registry` | Same file:32 | **Updated** to `"test-artifacts.v1.2"` — includes `:dependencies` |
| `ambiguous-registry` | Same file:103 | No `schema_version` field (legacy `:verifies_against` only) |
| Adapter tests | `test/resolver_sim/validation/adapters/artifact_registry_test.clj` | Uses check-map shape, not raw registry |

## 5. Configuration Entries

| Config File | `test-artifacts` Schema Version | `artifact_dir` | `runs_root` |
|---|---|---|---|
| `config/evidence.json` | `"test-artifacts.v1.1"` | `./prf-artifacts` | `./prf-runs` |
| `resources/config/evidence.json` | `"test-artifacts.v1.1"` | `results/test-artifacts` | `results/runs` |

Both agree on schema version. They diverge on default directories (CWD-relative vs resources-relative).

## 6. Schema-Version Constants

| Location | Constant | Value |
|---|---|---|
| `schemas/test-artifacts-v1.json` | `const` [DEPRECATED] | `"test-artifacts.v1"` |
| `schemas/test-artifacts-v1.1.json` | `enum` [DEPRECATED] | `["test-artifacts.v1.1"]` |
| `schemas/test-artifacts-v1.2.json` | `const` (current) | `"test-artifacts.v1.2"` |
| `config/evidence.json` → `schemas.test-artifacts` | Config key | **Updated** to `"test-artifacts.v1.2"` |
| `resources/config/evidence.json` → `schemas.test-artifacts` | Config key | **Updated** to `"test-artifacts.v1.2"` |
| `src/resolver_sim/evidence/config.clj` | Read from config | `"test-artifacts.v1.2"` (via updated config) |
| `scripts/evidence/evidence_config.py` | Read from config | `"test-artifacts.v1.2"` (via updated config) |
| `scripts/evidence/write_test_summary.py:441` | **Hardcoded** | **Updated** to `"test-artifacts.v1.2"` |

---

## Field Matrix

### Top-Level Fields

| Field | Emitted | Struct. Req'd | Semantically Req'd | Consumed By | Class |
|---|---|---|---|---|---|---|
| `schema_version` | YES | YES | YES | Python validator, Clojure loader, SchemaValidator | **CONTRACT** |
| `artifacts` | YES | YES (minItems: 1) | YES | All consumers | **CONTRACT** |
| `run_id` | YES | YES | YES | Python (run manifest cross-ref), Clojure (`:run-id`) | **CONTRACT** |
| `run_manifest` | YES (legacy only, not v1.1 emitter) | NO | NO | Python validator only (path, sha256 cross-ref) | **LEGACY** |
| `contract_version` | YES | NO | NO | — | INFORMATIONAL |
| `generated_at` | YES | NO | NO | — | INFORMATIONAL |
| `generator` | YES | NO | NO | — | INFORMATIONAL |
| `root_dir` | YES | NO | NO | — | INFORMATIONAL |

### Per-Artifact Fields

| Field | Emitted | Struct. Req'd (v1.2) | Semantically Req'd | Consumed By | Class |
|---|---|---|---|---|---|---|
| `id` | YES | YES | YES | All consumers | **CONTRACT** |
| `kind` | YES | YES | NO | — | **CONTRACT** (policy, not yet validated) |
| `path` | YES | YES | YES | Python (existence, hash), manifest loader | **CONTRACT** |
| `schema_version` | YES | YES | YES | Clojure (dep resolution), Python (cross-ref) | **CONTRACT** |
| `sha256` | YES | YES (pattern: `^[a-f0-9]{64}$`) | YES | Python, bundle verifier | **CONTRACT** |
| `importance` | YES | YES (enum: CORE/DIAGNOSTIC/TRACE) | NO | Emitter (filtering), no downstream validator | **CONTRACT** (policy) |
| `dependencies` | YES (v1.2 canonical) | YES (array of `{id, sha256}`) | NO | Clojure validator (canonical dep resolution) | **CANONICAL** |
| `verifies_against` | YES (legacy bridge) | YES (array of strings) | YES | Clojure validator (fallback dep resolution) | **DEPRECATED** (migration bridge) |
| `input_versions` | YES (legacy) | NO | NO | Python validator (single check) | **DEPRECATED** |
| `contract_version` | YES | NO | NO | — | INFORMATIONAL |
| `producer` | YES | NO | NO | — | INFORMATIONAL |
| `bytes` | YES | NO | NO | — | INFORMATIONAL |
| `mtime_utc` | YES | NO | NO | — | INFORMATIONAL |

---

## Contract Decisions

### 1. `run_manifest` — optional external relation, not part of registry contract

Only the legacy v1 producer writes it. Only the Python validator reads it (path/SHA256 cross-check). The v1.1 emitter (`write_scenario_run_manifest.py`) does NOT write it.

**Decision**: Optional top-level field in v1.2. Not required. Consumers that need it must handle absence.

### 2. `dependencies` — canonical dependency representation

Currently emitted but zero validators consume it. The Clojure validator uses `verifies_against` + `schema_version` for dependency resolution.

**Decision**: Make `dependencies` canonical in v1.2. At the loader boundary, translate `verifies_against` + `input_versions` into the canonical `dependencies` form with a deprecation warning. Consumers should prefer `dependencies` when present and fall back to legacy fields for migration.

### 3. Translation: `verifies_against` / `input_versions` → canonical `dependencies`

```
verifies_against = ["test-run.v1", "projection.v1"]
input_versions  = {"test_run": "test-run.v1"}

→ canonical
dependencies = [
  {"id": "test-run.v1",        "sha256": <from referenced artifact>},
  {"id": "projection.v1",      "sha256": <from referenced artifact>}
]
```

Translation occurs at the loader boundary only. Emitters should emit both during migration.

**Canonical representation** (v1.2 target):
```json
"dependencies": [
  {"id": "test-run", "sha256": "abc123..."},
  {"id": "test-summary", "sha256": "def456..."}
]
```

Where `id` is the artifact ID (not the schema version), matching the `dependencies` structure already emitted by `write_scenario_run_manifest.py` (lines 223-228).

### 4. Contract fields vs open `extensions` object

| Category | Fields | `additionalProperties` |
|---|---|---|
| **Contract — required** | `schema_version`, `run_id`, `artifacts` (top); `id`, `kind`, `path`, `schema_version`, `sha256`, `importance` (per-artifact) | `false` |
| **Contract — optional, explicit** | `contract_version`, `generated_at`, `generator`, `root_dir`, `run_manifest` (top); `contract_version`, `producer`, `dependencies`, `bytes`, `mtime_utc` (per-artifact) | `false` |
| **Contract — deprecated migration** | `verifies_against`, `input_versions` (per-artifact) | `false` |
| **Extensions** | `extensions` (top and per-artifact) | `true` within the `extensions` object only |

### 5. Summary of Required Fields in v1.2

**Top-level required**: `schema_version`, `run_id`, `artifacts`

**Per-artifact required**: `id`, `kind`, `path`, `schema_version`, `sha256`, `importance`

**Per-artifact optional, explicit**: `contract_version`, `producer`, `dependencies`, `verifies_against`, `input_versions`, `bytes`, `mtime_utc`, `extensions`

### 6. Structural Constraints

- `sha256`: pattern `^[a-f0-9]{64}$`
- `id`, `kind`, `path`, `schema_version`, `run_id`: `minLength: 1`
- `importance`: enum `["CORE", "DIAGNOSTIC", "TRACE"]`
- `dependencies[].{id, sha256}`: both `minLength: 1`
- `additionalProperties: false` at top-level and per-artifact (exception: `extensions` object)

### 7. Known Schema Versions Referenced in `verifies_against` but NOT Provided as Artifacts

These come from `config/evidence.json` → `exempt_schemas`:
- `evidence-contract.v1`
- `scenario.v1`
- `projection.v1`
- `test-run.v1` (special: provided by test-run artifact but also referenced externally)
- `test-summary.v2` (special: same)

These are exempt from dangling-dependency checks.

---

---
# Phase 7 — Cleanup Report

Completed: Phases 1–7. All code changes, configuration updates, fixture corrections, and comprehensive tests are done.

## Changes Made

### Phase 2 — Schema
- Created `schemas/test-artifacts-v1.2.json` with `additionalProperties: false`, required `run_id`, per-artifact `id`/`kind`/`path`/`schema_version`/`sha256`/`importance`, pattern constraints, and `extensions` for future metadata.

### Phase 3 — Structural Validator
- Created `scripts/evidence/schema_validator.py` — `SchemaValidator` class supports only `test-artifacts.v1.2`, returns path-aware errors (`$.artifacts[0].sha256`), rejects v1 and v1.1 at the resolve step.

### Phase 4 — Semantic Validation Separation
- `scripts/validate/validate_artifact_registry.py`: structural validation (`SchemaValidator().validate()`) runs first, prints `STRUCTURAL FAILURE` prefix, aborts before semantic checks.
- `scripts/validate/verify_artifact_registry.py`: structural validation rejects bad structures before integrity checks.

### Phase 5 — Producer/Consumer Migration
- `config/evidence.json`: `"test-artifacts.v1.1"` → `"test-artifacts.v1.2"`
- `resources/config/evidence.json`: same
- `scripts/evidence/write_scenario_run_manifest.py`: generator `v1.1` → `v1.2`, structural validation before write.
- `scripts/evidence/extract_scenario_artifacts.py`: structural validation on existing registry before appending entries.
- `scripts/evidence/write_test_summary.py`: hardcoded `"test-artifacts.v1"` → `"test-artifacts.v1.2"`
- `src/resolver_sim/validation/integration/artifact_registry.clj`: added `parse-dependencies` (canonical `:dependencies` field), `dangling-dependency-refs` check (resolve by `:id`), dynamic `dependency-resolution-mode` (`:dependencies-field` vs `:verifies-against-field`).
- `src/resolver_sim/validation/adapters/artifact_registry.clj`: no changes needed (already generic).

### Phase 6 — Fixtures & Tests
- `test/resolver_sim/validation/integration/artifact_registry_test.clj`: `realistic-registry` schema `"test-artifacts.v1"` → `"test-artifacts.v1.2"`, all artifacts include `:dependencies`, `test-dependency-resolution-mode` tests both modes.
- Created `scripts/evidence/test_artifact_contract_v1.2.py`: 19 integration tests covering structural validation, producer emission, and validator integration.
- `test/resolver_sim/validation/adapters/artifact_registry_test.clj`: check-map test values updated from `v1.1` to `v1.2`.

### Phase 6 — Cross-references cleaned
- `notebooks/telemetry.clj`: `"test-artifacts.v1"` → `"test-artifacts.v1.2"`
- `docs/reference/logging.md`: `test-artifacts.v1` → `test-artifacts.v1.2`
- `schemas/test-artifacts-v1.json`: title/description marked `[DEPRECATED]`
- `schemas/test-artifacts-v1.1.json`: title/description marked `[DEPRECATED]`
- `schemas/README.md`: added v1.2 as current, deprecated v1/v1.1 entries

## Remaining (low-priority / cosmetic)

- `schemas/test-artifacts-v1.json` and `schemas/test-artifacts-v1.1.json` are retained for historical validation but are not used by `schema_validator.py`. They could be removed once all v1/v1.1 registries are flushed from the pipeline.
- The `schemas/` directory still contains a `test-artifacts-v1.1.json` that `schema_validator.py` explicitly rejects at runtime. This is by design — the validator rejects old versions so producers are forced to upgrade.

## Test Coverage

| Suite | Tests | Assertions | Status |
|-------|-------|-----------|--------|
| Clojure `artifact-registry-test` | 19 | 88 | All pass |
| Python `test_artifact_contract_v1.2.py` | 19 | 19 | All pass |
| Python `schema_validator` (manual) | 7 | 7 | All pass |

**Total: 45 tests, 114 assertions, 0 failures.**
