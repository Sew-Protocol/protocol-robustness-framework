# ATTESTATION_SPEC_V1

Status: Draft V1

## 1. Purpose

Attestations are signed endorsements regarding evidence, claims, or executions.

An attestation is not evidence.

An attestation is not a claim.

An attestation is a statement by an actor that they have verified, observed, approved, or certified something.

Examples:

- CI verified execution
- Replay verifier confirmed determinism
- External auditor certified benchmark
- Human reviewer approved report

------

## 2. Design Principles

### 2.1 Attestations Are External

Attestations originate from actors.

Evidence originates from execution.

Claims originate from evaluation.

------

### 2.2 Attestations Are Verifiable

Attestations SHOULD be cryptographically verifiable.

------

### 2.3 Attestations Are Non-Authoritative

Evidence remains authoritative.

Attestations provide endorsements.

Evidence SHALL NOT require attestations to remain valid.

------

### 2.4 Attestations Are Immutable

Attestations SHALL NOT be modified.

Revocation SHALL be represented separately.

------

## 3. Attestation Structure

Canonical structure:

```clojure
{:attestation-id ...
 :attestor ...
 :subject ...
 :claim ...
 :timestamp ...
 :signature ...
 :metadata {...}}
```

------

## 4. Required Fields

### :attestation-id

Unique identifier.

Example:

```clojure
"uuid"
```

------

### :attestor

Attesting entity.

Examples:

```clojure
{:type :ci-runner
 :id "github-actions"}
{:type :auditor
 :id "audit-team"}
{:type :validator
 :id "node-12"}
```

------

### :subject

Object being attested.

Examples:

```clojure
{:type :evidence-node
 :hash "sha256:..."}
{:type :claim
 :claim-id :accounting-consistency}
```

------

### :claim

Attestation assertion.

Examples:

```clojure
:verified
:approved
:certified
:reproduced
```

------

### :timestamp

UTC timestamp.

Required.

------

## 5. Signature

Structure:

```clojure
{:algorithm ...
 :public-key-id ...
 :signature-bytes ...}
```

Cryptographic signatures are RECOMMENDED.

Unsigned attestations MAY exist but SHALL be explicitly marked.

------

## 6. Attestation Types

### Verification

Example:

```clojure
:verified
```

Meaning:

Subject was independently checked.

------

### Reproduction

Example:

```clojure
:reproduced
```

Meaning:

Independent execution produced equivalent result.

------

### Certification

Example:

```clojure
:certified
```

Meaning:

Subject satisfies predefined criteria.

------

### Approval

Example:

```clojure
:approved
```

Meaning:

Human or system approval granted.

------

### Rejection

Example:

```clojure
:rejected
```

Meaning:

Subject failed attestation requirements.

------

## 7. Revocation

Attestations SHALL NOT be deleted.

Revocations SHALL be separate records.

Example:

```clojure
{:revokes-attestation-id ...}
```

------

## 8. Multi-Attestor Consensus

Multiple attestations MAY exist for the same subject.

Example:

```clojure
[:verified
 :verified
 :verified]
```

Consensus interpretation is outside the scope of this specification.

------

## 9. Attestation Validation

Validation SHALL fail if:

- attestor missing
- subject missing
- claim missing
- timestamp missing

Signature validation SHALL fail if:

- signature malformed
- public key unavailable
- verification fails

------

## 10. Evidence Relationship

Evidence:

```clojure
execution produced result
```

Claim:

```clojure
result satisfies invariant
```

Attestation:

```clojure
independent actor verified claim
```

------

## 11. Provenance

Attestations SHOULD reference:

- evidence node hash
- claim id
- registry hash
- policy hash

when available.

This enables long-term traceability.

------

## 12. Long-Term Compatibility

Attestation meaning SHALL be determined by:

- attestation type
- attestor identity
- signature verification

Future schema evolution SHALL preserve verifiability of historical attestations.

------

## 13. Audit Requirement

Given:

- attestation
- public key
- referenced subject

an auditor SHALL be able to determine:

- who made the attestation
- what was attested
- when it was attested
- whether signature verification succeeds