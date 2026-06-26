# FORENSIC_WORKSPACE_SPEC_V1

Status: Draft V1

## 1. Purpose

Define the workspace classes for PRF development, forensic execution, and
private discovery. Establish what each workspace may contain, what operations
are allowed and forbidden, and where evidence and sensitive material reside.

This spec does not replace existing RUN_REQUEST_SPEC_V1, RUN_BUNDLE_ROOT_SPEC_V1,
RUN_OVERVIEW_SPEC_V1, or evidence-chain specs. It defines the **workspace
boundaries** that those specs operate within.

## 2. Design Principles

### 2.1 Separation by Purpose

A workspace is a context with a single primary purpose. No workspace tries
to be both a development environment and a forensic production environment.

### 2.2 Source vs Evidence

Source code and evidence are stored separately. The project repo contains
source, specs, tests, and public fixtures. Evidence output lives outside
the repo tree or in gitignored scratch directories.

### 2.3 Privacy is a First-Class Concern

Private discovery material MUST NOT be stored in the same workspace as
public evidence or source code. Private workspaces have their own isolation,
sealed-log, and export-path rules.

### 2.4 Content-Addressed Evidence

Forensic output is content-addressed and immutable once finalized. No
forensic run may overwrite an existing output directory. Each run produces
a unique bundle root hash.

### 2.5 Workspace Self-Description

Every workspace SHOULD have a README describing its purpose, what it
includes, what it excludes, and how to use it.

## 3. Workspace Classes

### 3.1 Project Root

| Property | Value |
|---|---|
| Purpose | Normal full development |
| Can edit source? | Yes |
| Can produce forensic evidence? | Candidate only — not yet verified |
| Can contain private material? | No |
| Git-tracked? | Yes (full repo) |

The project root (`resolver-sim/`) is the primary development workspace.
It includes all source code (`src/`, `protocols_src/`), tests (`test/`),
scenarios (`scenarios/`), specs (`docs/`), and public configuration.

Development runs (via `bb run:scenario`, `bb test:*`) write to
`results/runs/`, `results/evidence/`, `results/test-artifacts/` — all
gitignored scratch directories. Output in these directories is transient
and NOT treated as forensic evidence unless explicitly migrated via the
forensic runner.

**Forbidden in project root:**
- Private discovery material
- Sealed logs
- Encrypted notes
- Cryptographic keys (beyond test keys)
- Real forensic output bundles (candidate runs allowed)

### 3.2 PRF-Only Workspace

| Property | Value |
|---|---|
| Purpose | Framework-only development (no protocol noise) |
| Path | `workspaces/prf-only/` |
| Can edit source? | Yes (framework only) |
| Can produce forensic evidence? | No |
| Can contain private material? | No |
| Git-tracked? | Yes |

The PRF-only workspace exposes only the protocol-agnostic framework core.
It excludes `protocols_src/`, protocol-specific scenarios, forensic outputs,
and private discovery material.

**Included:**
- `src/` (framework core only)
- `test/` (all tests — protocol tests are harmless to have available but
  are not the focus)
- `docs/` (all docs)

**Excluded via project configuration:**
- `protocols_src/` — not in deps.edn paths
- `results/` — gitignored
- Forensic and discovery workspaces

**Useful for work on:**
- Registries (hash intent, claim definition, attestor)
- Canonical hashing
- Evidence DAG
- Attestation engine
- Execution runners
- Validation root
- Artifact schema

### 3.3 Protocol Development Workspace

| Property | Value |
|---|---|
| Purpose | Protocol implementation and scenario development |
| Path | `workspaces/with-sew/` (may exist as `workspaces/protocol-dev/` in future) |
| Can edit source? | Yes (framework + protocol) |
| Can produce forensic evidence? | Candidate only |
| Can contain private material? | No |
| Git-tracked? | Yes |

This is the full-stack view: framework core + protocol implementations.
It supports normal scenario runs, protocol-specific tests, replay, local
simulation, and dev-time artifact generation.

**Included:**
- `src/` (framework core)
- `protocols_src/` (protocol implementations)
- `test/` (all tests)
- `scenarios/` (all scenarios)

This workspace MUST NOT be treated as a forensic execution environment
unless invoked through the forensic runner with a preflight check.

### 3.4 Forensic Runner Workspace

| Property | Value |
|---|---|
| Purpose | Constrained evidence-producing execution |
| Path | `workspaces/forensic-runner/` (template, policies) |
| Execution output | `~/prf-runs/<run-id>/` (outside repo) |
| Can edit source? | Prefer no — read-only execution |
| Can produce forensic evidence? | Yes (primary purpose) |
| Can contain private material? | Limited — sealed evidence only |
| Git-tracked? | Template only; run output is external |

The forensic runner workspace is a **constrained execution environment**
for producing evidence that may later be relied on externally.

The workspace directory itself contains only policy templates, sample
inputs, and documentation. Actual forensic runs produce output in the
external archive at `~/prf-runs/<run-id>/`.

**Workspace contents:**
- `policies/` — evidence, execution, and output policy templates
- `inputs/` — sample run request and registry snapshot
- `outputs/` — staging (empty by default)
- `tmp/` — temporary files (gitignored)

**A forensic run requires:**
1. A declared run request (RUN_REQUEST_SPEC_V1)
2. A pinned registry snapshot (registry state hashes)
3. A runner identity (pinned or configured)
4. A hash intent registry present and valid
5. An evidence policy present and valid
6. A clean output directory (no prior run at target path)
7. No hidden scenario discovery (all scenarios declared)
8. Source snapshot recorded (git commit + dirty state)
9. Environment snapshot recorded (OS, tooling versions)
10. External network policy declared (allow/deny)

**Forbidden in forensic mode:**
- Unresolved dynamic runner selection unless recorded
- Unpinned registry state
- Missing hash intent registry
- Missing evidence policy
- Hidden scenario discovery
- Writing evidence into source directories
- Overwriting existing run output
- Non-empty dirty output directory
- Unsupported canonical hash values
- Missing runner identity
- Undeclared external network use
- Mutable timestamps inside stable hash projections
- Use of "latest" dependency coordinates without snapshotting

### 3.5 Private Discovery Workspace

| Property | Value |
|---|---|
| Purpose | Sensitive research before public disclosure |
| Path | `~/prf-private/<workspace-id>/` (outside repo) |
| Can edit source? | Drafts only — no direct source editing |
| Can produce forensic evidence? | Local/private only |
| Can contain private material? | Yes (primary purpose) |
| Git-tracked? | No — gitignored by default |

The private discovery workspace is for sensitive vulnerability research,
confidential scenario drafts, and sealed priority history. Its first
priority is **confidentiality**, not public reproducibility.

A template lives at `workspaces/private-discovery-template/` for
initializing new workspaces.

**Workspace structure (template):**
- `sealed-log/` — append-only event log (numeric entry files + head)
- `encrypted-inputs/` — at-rest encrypted scenario inputs
- `encrypted-notes/` — at-rest encrypted research notes
- `scenario-drafts/` — working scenario definitions
- `local-runs/` — local reproduction attempts (not yet forensic)
- `disclosure-candidates/` — sanitized findings ready for export
- `export/` — export path for forensic bundle creation

**Rules:**
- Gitignored by default
- Encrypted at rest (placeholder — real encryption TBD)
- Append-only sealed log
- Local evidence allowed
- Public anchoring optional and delayed
- Collaborator countersignatures supported (extension point)
- Export path creates sanitized forensic bundle later

**Forbidden in private discovery:**
- Automatic publication to IPFS, Nostr, GitHub, L1, or any public relay
- Sharing sealed material outside the workspace participant set
- Storing unencrypted keys or credentials in the workspace tree

## 4. Immutable Run Archive

Forensic run output resides outside the project repo at:

```
~/prf-runs/
└── 2026-06-26T17-30Z-sew-yield-suite/
    ├── input-manifest.json
    ├── source-snapshot.json
    ├── registry-snapshot.json
    ├── environment.json
    ├── run-request.json
    ├── preflight-report.json
    ├── evidence-dag/
    ├── claims/
    ├── attestations/
    ├── anchors/
    ├── run-overview.json
    └── run-bundle-root.json
```

Each run directory is named with a timestamp and human-readable label.
The `run-bundle-root.json` is the canonical bundle root
(RUN_BUNDLE_ROOT_SPEC_V1). The `run-overview.json` is the stable-field
overview (RUN_OVERVIEW_SPEC_V1).

Run directories MUST NOT be modified after creation. Any correction
produces a new run directory.

## 5. Export Path: Private Discovery → Forensic Run

The bridge between private discovery and public forensic evidence:

| Step | Private Workspace | Export Action | Forensic Archive |
|---|---|---|---|
| 1 | Sealed log entry documenting the finding | Selective extraction | Run request provenance note |
| 2 | Scenario draft in `scenario-drafts/` | Sanitize (redact sensitive details) | `inputs/scenario-set/` |
| 3 | Local run in `local-runs/` | Verify against forensic policy | `evidence-dag/` |
| 4 | Claim definition | Register if not already registered | Signed claim result |
| 5 | Disclosure candidate | Final sanitized summary | Attestation + disclosure-safe summary |

The export SHOULD support redaction:
- Researcher notes → omitted from forensic bundle
- Private hypotheses → omitted
- Exploit sketches → omitted or summarized
- Vulnerable addresses → abstracted
- Reproduction scenario → included (sanitized)

The sealed private log proves internal history and priority if needed,
but not everything must be public.

## 6. Workspace Boundary Table

| Workspace | Purpose | Edit Source? | Produce Forensic Evidence? | Contain Private Material? |
|---|---|---|---|---|
| Project root | Normal full development | Yes | Candidate only | No |
| `workspaces/prf-only/` | Framework-only work | Yes (framework) | No | No |
| `workspaces/with-sew/` | Protocol implementation | Yes | Candidate only | No |
| `workspaces/forensic-runner/` | Evidence-producing execution | Prefer no | Yes | Limited |
| `~/prf-private/<id>/` | Sensitive research | Drafts only | Local/private only | Yes |
| `~/prf-runs/<run-id>/` | Immutable run archive | No | Yes | Usually no |

## 7. Source Control Rules

| Workspace | In Git? | Notes |
|---|---|---|
| Project root | Yes | Full repo |
| `workspaces/prf-only/` | Yes | README + deps.edn only |
| `workspaces/with-sew/` | Yes | README + deps.edn only |
| `workspaces/forensic-runner/` | Yes | Templates and policies only |
| `workspaces/private-discovery-template/` | Yes | Empty template structure only |
| `~/prf-runs/` | No | External archive |
| `~/prf-private/` | No | External archive |

## 8. References

- RUN_REQUEST_SPEC_V1 — canonical run request format
- RUN_BUNDLE_ROOT_SPEC_V1 — bundle root format
- RUN_OVERVIEW_SPEC_V1 — stable-field overview format
- FORENSIC_PREFLIGHT_SPEC_V1 — preflight check specification
- EVIDENCE_POLICY_SPEC_V1 — evidence policy specification
- HASH_INTENT_REGISTRY_SPEC_V1 — hash intent registry specification
- ATTESTATION_BUNDLE_SPEC_V1 — attestation bundle specification
- CLAIM_DEFINITION_REGISTRY_SPEC_V1 — claim definition registry
