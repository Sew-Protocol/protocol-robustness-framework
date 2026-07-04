# Forensic Runner Workspace

A constrained execution environment for producing evidence that may later
be relied on externally.

This workspace is a **template**. It contains policy definitions, sample
inputs, and documentation. Actual forensic runs produce output in the
external archive at `~/prf-runs/<run-id>/`.

## Lifecycle

1. **Prepare** — Create a run request and registry snapshot
2. **Preflight** — Run `bb forensic:preflight` to validate readiness
3. **Execute** — Run `bb forensic:run` to produce evidence
4. **Verify** — Run `bb forensic:verify` to validate the bundle
5. **Archive** — The bundle at `~/prf-runs/<run-id>/` is the durable record

## Directory Structure

```
workspaces/forensic-runner/
├── README.md
├── policies/
│   ├── evidence-policy.edn   # What evidence is captured
│   ├── execution-policy.edn  # How execution is constrained
│   └── output-policy.edn     # Where and how output is written
├── inputs/
│   ├── run-request.edn       # Sample run request (edit for your run)
│   ├── registry-snapshot.edn # Sample registry snapshot (edit for your run)
│   └── scenario-set/         # Place scenario files here
├── outputs/                  # Staging (empty by default)
└── tmp/                      # Temporary files (gitignored)
```

## Forensic Execution Rules

- No writes to source directories
- No overwriting existing evidence
- All scenarios explicitly declared (no ambient discovery)
- External network policy must be declared
- Runner identity must be pinned
- Output is content-addressed and immutable after creation

## Configuration (Environment Variables)

All hardening and path constants can be overridden without editing source files:

| Variable | Default | Purpose |
|---|---|---|
| `PRF_SOURCE_ROOTS` | `src,protocols_src,benchmarks,data/concepts,scenarios,suites,resources` | Replay-critical source directories for `code-hash` computation |
| `PRF_CODE_HASH_ALGORITHM` | `source-tree-hash.v1.path-content-sha256` | Algorithm name in bundle metadata |
| `PRF_RUNS_ROOT` | `~/prf-runs` | Base output directory for forensic runs |
| `PRF_ARTIFACT_DIR` | `results/test-artifacts` | Clojure pipeline output directory |
| `PRF_EVIDENCE_USER` | `evidence_runner` | Expected user for UID check |
| `PRF_EVIDENCE_WORKSPACE` | `/var/lib/evidence-runner` | Expected workspace for fs-access check |

Example:

```bash
PRF_SOURCE_ROOTS="src,protocols_src,benchmarks,data/concepts,scenarios,suites,resources" \
PRF_RUNS_ROOT=/mnt/evidence-archive \
PRF_EVIDENCE_USER=dedicated-runner \
  bb forensic:run
```

See `docs/forensic/FORENSIC_HARDENING.md` for full documentation.

## Commands

| Command | Description |
|---|---|
| `bb forensic:run` | Execute a forensic run (preflight + execution + verification + hardening) |
| `bb forensic:preflight` | Run preflight checks only |
| `bb forensic:verify <dir>` | Verify a forensic run bundle |
| `bb forensic:export <dir>` | Export bundle as portable tar.gz |
| `bb forensic:import <archive>` | Import bundle from tar.gz |
| `bb forensic:reproduce <dir>` | Reproduce a run from its bundle and compare |
| `bb forensic:self-test` | Validate pipeline determinism (run twice, compare) |

## Lifecycle

1. **Prepare** — Create a run request and registry snapshot
2. **Preflight** — Run `bb forensic:preflight` to validate readiness
3. **Execute** — Run `bb forensic:run` to produce evidence
4. **Verify** — Run `bb forensic:verify` to validate the bundle
5. **Reproduce** — Run `bb forensic:reproduce` to confirm determinism
6. **Export** — Run `bb forensic:export` to create portable archive
7. **Archive** — The bundle at `~/prf-runs/<run-id>/` is the durable record

## See Also

- `GUIDE.md` — Full usage guide with examples and output layout
- `docs/forensic/FORENSIC_WORKSPACE_SPEC_V1.md`
- `docs/forensic/FORENSIC_PREFLIGHT_SPEC_V1.md`
- `docs/forensic/FORENSIC_HARDENING.md`
- `docs/forensic/PRODUCTION_GAPS.md`
- `docs/RUN_REQUEST_SPEC_V1.md`
- `docs/RUN_BUNDLE_ROOT_SPEC_V1.md`
