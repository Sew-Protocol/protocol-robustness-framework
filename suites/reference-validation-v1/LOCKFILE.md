# Reference Validation Suite v1 Lockfile

## Suite

- Suite ID: reference-validation-v1
- Suite version: 1.0.0
- Status: public-candidate

## Repository state

- sew-simulation commit: d6a588a9c422e0cd4e11d49d706c9858f7ef0c7c
- sew-protocol commit: N/A (simulation repository context)
- generated at: 2026-05-12T12:54:00Z

## Runtime

- Clojure version: pinned by deps.edn (org.clojure/clojure 1.12.0)
- Java version: 21 (CI target)
- Python version: 3.11 (CI target)
- OS used for expected outputs: Linux (ubuntu-latest)
- Docker image: optional; not required for v1

## Scenario/config versions

- suite config: reference-config-v1
- economic assumptions: economic-assumptions-v1
- resolver capacity assumptions: resolver-capacity-v1
- chain assumptions: chain-assumptions-v1

## Determinism

This suite is expected to produce byte-stable canonical JSON outputs when run with the pinned configuration.

If local runtime metadata differs, result semantics should remain identical, but exact hashes may differ unless canonical JSON normalization is used.

Canonical JSON formatting is used for expected outputs and hash comparisons.
