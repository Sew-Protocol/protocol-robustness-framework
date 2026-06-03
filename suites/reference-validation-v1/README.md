# Reference Validation Suite v1

Reference Validation Suite v1 is currently a **deterministic public evidence harness**.

## Status

Public candidate, **v1.1 honest hybrid**.

## Evidence classification

- 1 scenario is simulator-backed (001 governance-sandwich)
- 6 scenarios are pinned derivations

Simulator-backed rows are produced only by `resolver-sim.sim.reference-validation` replay + trace export (see `manifest.edn`).

## Commands

```bash
make reference-validation-v1          # write actual/
make verify-reference-validation-v1   # compare actual/ vs expected/
clojure -M:reference-validation --refresh-expected   # refresh pinned hashes after intentional output changes
./scripts/test.sh reference-validation
```

Draft scenarios under `draft/expected/` are not part of the CI gate.
