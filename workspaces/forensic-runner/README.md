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

## See Also

- `docs/forensic/FORENSIC_WORKSPACE_SPEC_V1.md`
- `docs/forensic/FORENSIC_PREFLIGHT_SPEC_V1.md`
- `docs/RUN_REQUEST_SPEC_V1.md`
- `docs/RUN_BUNDLE_ROOT_SPEC_V1.md`
