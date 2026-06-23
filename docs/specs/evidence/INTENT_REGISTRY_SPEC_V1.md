# Intent Registry Specification V1

## Status

Normative

## Purpose

The Intent Registry defines all valid identity domains within the system.

It provides:

* Semantic governance
* Domain-tag uniqueness guarantees
* Identity Algebra enforcement
* Startup validation requirements
* Compatibility rules

The registry is the authoritative source of truth for all hash intents.

---

# 1. Registry Model

Each intent MUST be registered exactly once.

An intent definition consists of:

```clojure
{:intent/name keyword
 :intent/domain-tag string
 :intent/description string
 :intent/includes #{keyword}
 :intent/excludes #{keyword}
 :intent/projection-fn symbol
 :intent/version integer}
```

---

# 2. Required Fields

## :intent/name

Globally unique keyword.

Example:

```clojure
:world-structure
```

---

## :intent/domain-tag

Globally unique domain separator.

Example:

```clojure
"WORLD_STATE_V1"
```

Requirements:

* immutable once published
* unique across registry
* uppercase ASCII
* stable and human-readable
* version suffix may be used when appropriate

---

## :intent/description

Human-readable semantic contract.

Must answer:

> What identity question does this hash represent?

---

## :intent/includes

Concepts intentionally represented.

Example:

```clojure
#{:topology
  :configuration
  :oracle-state}
```

---

## :intent/excludes

Concepts intentionally excluded.

Example:

```clojure
#{:functions
  :runtime-behavior
  :transient-values}
```

---

## :intent/projection-fn

Projection function used before canonical encoding.

Example:

```clojure
resolver-sim.hash.canonical/project-world-to-structure-view
```

---

## :intent/version

Monotonic integer.

Projection changes require increment.

---

# 3. Identity Algebra Invariants

The registry MUST satisfy all invariants.

---

## IA-001 Unique Intent Names

For all intents:

```text
intent_a ≠ intent_b
```

---

## IA-002 Unique Domain Tags

For all intents:

```text
domain_tag_a ≠ domain_tag_b
```

---

## IA-003 Domain Isolation

Distinct intents MUST NOT share domain tags.

---

## IA-004 Intent Ownership

Every hash produced by the system MUST originate from a registered intent.

---

## IA-005 Projection Declaration

Every intent MUST declare its projection function.

Identity generation without a declared projection is invalid.

---

## IA-006 Versioned Semantic Changes

Changes to:

* projection semantics
* inclusion rules
* exclusion rules
* encoding semantics

require:

* new version
* new domain tag

Example:

```text
WORLD_STATE_V1
WORLD_STATE_V2
```

---

## IA-007 Startup Validation

The system MUST validate registry integrity during startup.

Failure is fatal.

---

# 4. Current Registry

## world-structure

Domain:

```text
WORLD_STATE_V1
```

Purpose:

Structural world identity.

---

## evm-projection

Domain:

```text
EVM_PROJECTION_V1
```

Purpose:

EVM-comparable projection identity.

---

## evidence-record

Domain:

```text
EVIDENCE_RECORD_V1
```

Purpose:

Audit event identity.

---

## evidence-content

Domain:

```text
EVIDENCE_CONTENT_V1
```

Purpose:

Canonicalized evidence payload identity.

---

## evidence-chain

Domain:

```text
EVIDENCE_CHAIN_V1
```

Purpose:

Evidence linkage identity.

---

## manifest

Domain:

```text
MANIFEST_V1
```

Purpose:

Manifest identity.

---

## bundle-root

Domain:

```text
BUNDLE_ROOT_V1
```

Purpose:

Bundle root identity.

---

## registry

Domain:

```text
REGISTRY_V1
```

Purpose:

Registry state identity.

---

## provenance

Domain:

```text
PROVENANCE_V1
```

Purpose:

Lineage identity.

---

# 5. Extension Procedure

Adding a new intent requires:

1. Registry entry
2. Unique domain tag
3. Projection definition
4. Tests
5. Changelog entry
6. Spec update

Failure to satisfy all requirements invalidates the intent.

---

# 6. Compliance

A system is compliant if:

* all intents are registered
* startup validation succeeds
* all hashes originate from registered intents
* all Identity Algebra invariants hold

