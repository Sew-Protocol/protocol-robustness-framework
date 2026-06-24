# HASH_INTENT_REGISTRY_SPEC_V1

Status: Stable

## 1. Purpose

Defines the executable canonical hash intent registry used by `hash-with-intent` in `resolver-sim.hash.canonical`.

This registry is the single source of truth for all framework hash intents. Every call to `hash-with-intent` resolves against this registry at runtime. Unregistered intents are rejected with an error.

The registry exists to make every hash answer the question:

> "What identity domain is this hash committing to?"

Without an explicit intent, two structurally similar values from different semantic domains could accidentally share hash semantics. That would weaken evidence integrity, replay verification, registry validation, and forensic review.

## 2. Design Principles

### 2.1 Intent Is Registered

All valid hash intents SHALL be registered in `resolver-sim.hash.canonical/hash-intents`. An unregistered intent SHALL cause `hash-with-intent` to throw.

### 2.2 Intent Is Stable

A registered intent definition SHALL NOT change meaning. Semantic changes require a new intent version.

### 2.3 Intent Is Domain-Separated

Every intent SHALL have a unique domain tag string prepended to the canonical bytes before hashing. No two intents SHALL share a domain tag.

### 2.4 Intent Is Projected

Every intent SHALL define a projection function that transforms the input value into a canonical-safe subset before hashing. The projection function SHALL be deterministic and SHALL produce only canonical-compatible values.

### 2.5 Intent Is Enforced at Startup

The registry SHALL be validated at namespace load by `validate-registry!`. A startup hard-fail occurs if any intent contract is invalid, non-deterministic, or produces non-canonical output.

## 3. Registry Source

The intent registry is defined in:

```
src/resolver_sim/hash/canonical.clj
```

```clojure
(def hash-intents
  {:intent-key {...intent-contract...}
   ...})
```

The registry is a map from intent keyword to intent contract map. Each key SHALL match the contract's `:intent/name` field.

## 4. Intent Contract Shape

Each intent contract is a map with exactly the following 7 required fields:

```clojure
{:intent/name          :evidence-node
 :intent/domain-tag    "EVIDENCE_NODE_V1"
 :intent/description   "Canonical identity of an execution evidence node"
 :intent/includes      #{:schema-version :parent-hashes :bootstrap-roots
                         :execution :result :evidence :attestations :extensions}
 :intent/excludes      #{:node-id :node-hash :timestamp :policy-output
                         :visible-failures :filtered-output :runtime-values}
 :intent/projection-fn project-evidence-node
 :intent/version       1}
```

### 4.1 `:intent/name`

Keyword identifying the intent. MUST match the key in the `hash-intents` map.

- Type: `keyword?`
- Required: yes

### 4.2 `:intent/domain-tag`

ASCII string domain tag prepended to canonical bytes before hashing. MUST be unique across all intents. MUST be registered in `resolver-sim.hash.canonical/domain-tags`.

- Type: `string?`
- Required: yes
- Constraint: globally unique

### 4.3 `:intent/description`

Human-readable description of the intent's semantic domain.

- Type: `string?`
- Required: yes

### 4.4 `:intent/includes`

Set of top-level keys included in the hash projection. Documents the data surface that the projection function preserves.

- Type: `set?`
- Required: yes

### 4.5 `:intent/excludes`

Set of top-level keys excluded from the hash projection. Documents the data surface that the projection function strips.

- Type: `set?`
- Required: yes

### 4.6 `:intent/projection-fn`

Function that transforms the input value into canonical-safe data for hashing. Called as `(projection-fn value intent-kw)`. MUST be deterministic (same input → same output). MUST produce only canonical-compatible values (checked at startup with a sample input).

The projection function is responsible for:

- stripping excluded fields
- transforming runtime-specific types (sets, ratios, etc.) into canonical-safe representations
- ensuring the output can be encoded by the canonical binary encoding

- Type: `fn?`
- Required: yes
- Constraints: deterministic, canonical-safe output

### 4.7 `:intent/version`

Monotonic version number for the intent contract definition. SHALL be incremented when the contract's projection or exclusion rules change.

- Type: `(every-pred integer? pos?)` (positive integer)
- Required: yes

## 5. Validation Rules (`validate-registry!`)

The function `resolver-sim.hash.canonical/validate-registry!` SHALL be called at namespace load. It enforces:

### 5.1 All 7 Fields Present

Every contract SHALL contain all 7 required fields. Missing fields cause a hard-fail with message `"Intent <name> missing required field <field>"`.

### 5.2 All 7 Fields Have Correct Types

Every field SHALL satisfy its type predicate:

| Field | Predicate |
|---|---|
| `:intent/name` | `keyword?` |
| `:intent/domain-tag` | `string?` |
| `:intent/description` | `string?` |
| `:intent/includes` | `set?` |
| `:intent/excludes` | `set?` |
| `:intent/projection-fn` | `fn?` |
| `:intent/version` | `(every-pred integer? pos?)` |

### 5.3 Map Key Matches `:intent/name`

Each key in `hash-intents` SHALL equal the contract's `:intent/name` value.

### 5.4 Projection Is Deterministic

Calling `(:intent/projection-fn contract)` twice with the same sample input SHALL produce identical results.

### 5.5 Projection Produces Canonical-Safe Data

The projection output SHALL pass `validate-canonical-value!` without throwing.

### 5.6 Domain Tag Is Registered

`:intent/domain-tag` SHALL appear in `resolver-sim.hash.canonical/domain-tags`.

### 5.7 Domain Tags Are Unique

No two intents SHALL share the same `:intent/domain-tag`. Violations cause a hard-fail with message `"Intent domain tags must be unique"`.

## 6. Runtime Enforcement (`hash-with-intent`)

The function `resolver-sim.hash.canonical/hash-with-intent` enforces the registry at every call:

```
Input:  {:hash/intent intent-keyword} value
Steps:
  1. Resolve intent-keyword via resolve-intent
     - Found:   return the contract map
     - Not found: throw "Unknown hash intent" with known intents
  2. Optionally validate constraints against :intent/excludes
     (when *validate-intent-constraints* is true, enabled in tests)
  3. Compute hash: (domain-hash domain-tag (projection-fn value intent-keyword))
Output: hex string (64 characters)
```

### 6.1 Constraint Validation

When `*validate-intent-constraints*` is true, `validate-intent-constraints!` checks that the input value does not contain excluded keys. This catches silent exclusion drift during development and testing.

### 6.2 Registration Is Required

An unregistered intent SHALL NOT be accepted. Any keyword passed as `:hash/intent` that is not a key in `hash-intents` causes `resolve-intent` to throw immediately.

## 7. Domain Tags

Domain tags are defined in `resolver-sim.hash.canonical/domain-tags`:

```clojure
(def domain-tags
  {:world-state            "WORLD_STATE_V1"
   :evidence-record        "EVIDENCE_RECORD_V1"
   :evidence-chain         "EVIDENCE_CHAIN_V1"
   :evidence-content       "EVIDENCE_CONTENT_V1"
   :manifest               "MANIFEST_V1"
   :bundle-root            "BUNDLE_ROOT_V1"
   :registry               "REGISTRY_V1"
   :provenance             "PROVENANCE_V1"
   :state-diff             "STATE_DIFF_V1"
   :params-manifest        "PARAMS_MANIFEST_V1"
   :evm-projection         "EVM_PROJECTION_V1"
   :invariant-attestation  "INVARIANT_ATTESTATION_V1"
   :projection-evidence    "PROJECTION_EVIDENCE_V1"
   :checkpoint-evidence    "CHECKPOINT_EVIDENCE_V1"
   :benchmark-certification "BENCHMARK_CERTIFICATION_V1"
   :intent-dsl             "INTENT_DSL_V1"
   :intent-registry-entry  "INTENT_REGISTRY_ENTRY_V1"
   :intent-registry        "INTENT_REGISTRY_V1"
   :projection-definition  "PROJECTION_DEFINITION_V1"
   :projection-definition-registry "PROJECTION_DEFINITION_REGISTRY_V1"
   :projection-artifact    "PROJECTION_ARTIFACT_V1"
   :evidence-node          "EVIDENCE_NODE_V1"
   :decision-evidence      "DECISION_EVIDENCE_V1"
   :invariant-failure      "INVARIANT_FAILURE_V1"
   :startup-validation     "STARTUP_VALIDATION_V1"})
```

The domain tag map serves as a registry of domain tag strings. Every `:intent/domain-tag` in `hash-intents` SHALL be a value in this map. Not every entry in this map need have a corresponding intent contract — some tags serve backward-compatible `domain-hash` calls.

## 8. Adding a New Intent

1. Add a domain tag string to `domain-tags`
2. Add the intent contract to `hash-intents`
3. Ensure `:intent/domain-tag` matches the new domain tag string
4. Ensure `:intent/projection-fn` is deterministic and produces canonical-safe output
5. Add a matching entry to `intent-definitions` in `resolver-sim.definitions.passive-registries` if the intent should appear in the passive intent registry
6. Validate automatically at next namespace load — `validate-registry!` will either pass or throw

## 9. Relationship to `INTENT_REGISTRY_SPEC_V1`

`INTENT_REGISTRY_SPEC_V1` defines the passive intent registry in `resolver-sim.definitions.passive-registries`. That registry wraps `hash-intents` into a validated artifact format with registry-level hashing, claim definitions, and full validation via `validate-passive-registries!`.

`HASH_INTENT_REGISTRY_SPEC_V1` (this document) defines the executable runtime registry in `resolver-sim.hash.canonical`. This registry is the source of truth for `hash-with-intent` resolution. The passive registry mirrors it through `hash-projection-registry-definitions`.

Both registries are validated at startup. A failure in either prevents system start.
