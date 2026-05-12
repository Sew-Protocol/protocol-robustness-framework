# Reproducibility Notes

- Inputs are pinned via `SUITE.yaml`, `VERSION`, `LOCKFILE.md`, and `configs/*.edn`.
- `scripts/run.sh` emits canonical compact JSON without wall-clock fields.
- Expected outputs are committed under `expected/` with SHA-256 files.
- `scripts/verify.sh` checks presence, byte-equality, and hash equality.
- Runtime assumptions target Linux + Java 21 + Clojure 1.12 + Python 3.11.
