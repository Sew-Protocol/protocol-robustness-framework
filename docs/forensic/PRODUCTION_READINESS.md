# Production Readiness Assessment

Current state of the forensic runner system against production requirements.
Identifies gaps, stubbed features, and priorities for closing.

## Legend

- ✅ **Implemented** — meets production requirements
- ◐ **Partial** — functional but needs hardening
- 🟡 **Stub** — placeholder exists, no real implementation
- ❌ **Gap** — not implemented, blocks production use

---

## Sealing & Integrity

| Area | Status | Detail |
|---|---|---|
| Atomic file writes (temp → fsync → rename → readback → verify) | ✅ | `write_sealed_json` |
| Self-referential hashing (bundle root + overview) | ✅ | SHA-256 of canonical fields |
| Bundle signing (Ed25519) | ✅ | `--sign <key>` flag |
| Signature verification | ✅ | `--public-key` flag |
| Post-run output hardening | ✅ | `chmod -R a-w` + `chattr +i` |
| Content hash vs record hash split | ✅ | `execution/content-hash` + `execution/record-hash` |

## Verification

| Area | Status | Detail |
|---|---|---|
| File existence checks | ✅ | 6 required + 8 optional |
| Directory existence checks | ✅ | 4 required + 1 optional |
| Bundle root structural validation | ✅ | Schema version, required keys |
| Self-referential hash recomputation | ✅ | Bundle root + overview |
| Ed25519 signature verification | ✅ | With `--public-key` |
| Preflight report validation | ✅ | Status check |
| **Content validation of `clojure-bundle-root.json`** | ❌ | Not validated by verify.py — listed as optional file only |
| **Content validation of `evidence-dag-inventory.json`** | ❌ | Completely invisible to verify.py |
| **Validation of `claims/`, `attestations/`, `anchors/` content** | ❌ | Directories exist but content never checked |
| **Evidence DAG file format/structure validation** | ❌ | `check_evidence_dag` counts files, doesn't parse |

## Execution & Isolation

| Area | Status | Detail |
|---|---|---|
| Private tmpfs via unshare | ✅ | `--private-tmpfs` flag |
| OS-level isolation checks (5) | ✅ | UID, ptrace, /proc, fs-access, privileges |
| Composite isolation grade | ✅ | `full` → `good` → `partial` → `basic` |
| Configurable env vars | ✅ | 12 `PRF_*` variables |
| **Concurrent run safety** | ✅ | See below |

### Concurrent Run Safety

**Current state:** Three-layer protection implemented.

| Protection | Detail | Risk Resolved |
|---|---|---|
| **File lock** (`fcntl.flock`) | Exclusive lock on `results/.forensic-run.lock` — auto-releases on process death | Prevents concurrent orchestrator runs |
| **Per-run workspace** (`PRF_ARTIFACT_DIR`) | Each run gets an isolated `workspace/` dir inside its bundle. The Clojure pipeline writes all artifacts there instead of the shared `results/test-artifacts/`. Config bridge in `resolver-sim.evidence.config/artifact-dir` checks env var first. | Closes the critical gap even if the file lock were released |
| **Private tmpfs** (`--private-tmpfs`) | `unshare --user --map-root-user -m` mounts an isolated tmpfs | OS-level filesystem isolation |
| Quorum runs are sequential | `quorum.py` runs N executions sequentially (no parallelism) | Acceptable by design |

## Error Recovery

| Area | Status | Detail |
|---|---|---|
| Clojure pipeline crash | ❌ | Partial bundle left on disk, made immutable by hardening step |
| Preflight failure | ✅ | Aborts before any output |
| Dry-run | ✅ | Preflight only, no side effects |
| Temp file cleanup on write failure | ✅ | `try/finally` in `write_sealed_json` |
| **Post-crash cleanup** | ❌ | No mechanism to remove partial bundles |
| **Output hardening on failure** | ❌ | `make_output_immutable` runs even when pipeline exits non-zero |

**Resolution:** Roll back to `make_output_immutable` if `exit_code != 0`. Or: not-hardened bundles are easier to clean up.

## Output Retention — Critical Gap

**Current state:** No cleanup logic exists anywhere.

| Issue | Detail |
|---|---|
| Each run creates a new directory in `~/prf-runs/` | No limit on count or total size |
| No retention policy | No TTL, no max-runs, no max-size |
| No disk monitoring | No check for available space before starting |
| No automatic archival | Export is manual (`bb forensic:export`) |
| No post-export cleanup | Export doesn't remove the source directory |
| Evidence node duplication | Each run copies evidence nodes from `results/test-artifacts/` into its bundle, multiplying disk usage |

**Resolution:** Add `--retention <N>` to `bb forensic:run` (keep N most recent). Add `PRF_MAX_RUNS` env var. Add disk space check to preflight.

## Key Management

| Area | Status | Detail |
|---|---|---|
| Ed25519 signing | ✅ | Via Clojure `resolver-sim.benchmark.signing` |
| Key format support | ◐ | OpenSSH + PKCS#8. Encrypted keys explicitly rejected. |
| Key rotation | ❌ | No mechanism to cycle keys |
| Key revocation | ❌ | No CRL, no expiry |
| Public key distribution | ❌ | Manual `--public-key` argument only |
| HSM / secret store | ❌ | Keys are raw files on disk |

**Resolution:** Acceptable for development. Production would need integration with a key management system (e.g., Vault, KMS, TPM).

## Stubbed Features

These are directories, fields, or commands that exist as placeholders
but have no real implementation.

| Feature | Location | Status | Action |
|---|---|---|---|
| `claims/` directory | `forensic_populate.clj` | ✅ Populated with 3 claim results (registry-hash-verifies, cursor-verifies, forensic-grade composite) | Verify enforces hash integrity and schema version |
| `attestations/` directory | `forensic_populate.clj` | ✅ Populated with 3 self-attestations | Verify enforces hash integrity and schema version |
| `anchors/anchor-cursor.json` | `run.py` + `verify.py` | ✅ RFC 3161 or local-proof; validated by verify.py | Fully implemented — `--tsa-url` flag, TSA token copied to `anchors/`, verify checks content |
| `evidence-dag-inventory.json` (Phase B) | `scripts/forensic/run.py` | 🟡 Inventory-only, no semantic DAG | Implement DAG parsing or mark as permanent limitation |
| `evidence-dag-manifest.json` | Never created | 🟡 Referenced in design docs but not implemented | Create or remove from spec |
| `--no-harden` flag | `run.py` CLI | ◐ Works but should be default-on-failure | Skip hardening when pipeline fails exit code != 0 |

## Skipped Functionality (discussed but deferred)

| Feature | Phase | Notes |
|---|---|---|
| RFC 3161 timestamp anchoring | Deferred | Requires TSA client |
| IPFS/IPLD publication | Deferred | Requires IPFS node |
| L1 anchoring | Deferred | Requires blockchain integration |
| Multi-runner remote quorum | Deferred | Infrastructure work |
| Seccomp / capability dropping | Deferred | OS-level hardening |
| Read-only root filesystem (pivot_root) | Deferred | OS-level hardening |
| Dependency lock snapshot (classpath hash) | Deferred | Requires Maven/Basis resolution |
| Per-scenario failure summaries in bundle | Deferred | Requires Clojure output file parsing |
| EDN semantic DAG parsing | Deferred | Phase B Clojure-side work |
| Signed chain-of-custody log | Deferred | Per-run signing key infrastructure |
| HMAC per-run key | Deferred | Key distribution problem |

## Test Coverage — Critical Gap

| Area | Status | Detail |
|---|---|---|
| Clojure pipeline tests | ✅ | 335+ unit tests, invariants, generators |
| **Forensic Python script tests** | ❌ | Zero tests for `run.py`, `verify.py`, `preflight.py`, `reproduce.py`, `quorum.py`, `export.py`, `import_archive.py`, `isolation_checks.py` |
| Self-test is not a test | 🟡 | `bb forensic:self-test` validates Clojure determinism, not the Python orchestration layer |

**Resolution:** The Python scripts need unit tests. At minimum: mock-based tests for `verify.py` (parse a known-good bundle), `preflight.py` (validate known-good and known-bad run requests), `export.py`/`import_archive.py` (round-trip).

## Summary by Severity

### Critical (blocks unattended production use)

| # | Issue | Effort |
|---|---|---|
| 1 | **Concurrent run safety** — shared `results/test-artifacts/` | Medium — isolate per-run artifact dir or add file lock |
| 2 | **Output retention** — no cleanup, guaranteed disk fill | Low — add retention policy to preflight/run |
| 3 | **Test coverage** — zero tests for forensic scripts | High — need test framework + fixture data |

### High (significantly reduces trust)

| # | Issue | Effort |
|---|---|---|
| 4 | **Verify.py content validation** — clojure-bundle-root.json not validated | Low — add schema + hash checks |
| 5 | **Partial bundle on crash** — immutable leak | Low — skip hardening on non-zero exit |
| 6 | **Key management** — no rotation, revocation, or distribution | Medium — requires KMS integration |
| 7 | **Anchor cursor** — mock only, no timestamp anchoring | Medium — RFC 3161 TSA client |

### Medium (important for adoption)

| # | Issue | Effort |
|---|---|---|
| 8 | Claims/attestations directories are dead stubs | ✅ Resolved — populated by Clojure pipeline, verified by verify.py |
| 9 | Evidence DAG manifest is inventory-only | Medium — Phase B Clojure-side work |
| 10 | evidence-dag-inventory.json not verified | Low — add to verify.py optional file list |

## Recommended Immediate Actions

| Priority | Action | Effort | Impact |
|---|---|---|---|
| P0 | Add per-run isolation for `results/test-artifacts/` | Low | Closes critical concurrency gap |
| P0 | Add retention policy (max runs, disk check) | Low | Prevents disk exhaustion |
| P0 | Skip output hardening on non-zero exit | Trivial | Enables post-crash cleanup |
| P1 | Add `clojure-bundle-root.json` validation to verify.py | Low | Closes content validation gap |
| P1 | Add `evidence-dag-inventory.json` to verify.py optional files | Trivial | Makes inventory visible to verification |
| P1 | Remove stubbed `claims/` and `attestations/` directories | ✅ Done — populated by `forensic_populate.clj`, verified by `verify.py` | Eliminates dead structure |
| P2 | Add unit tests for verify.py (known-good bundle fixture) | Medium | Prevents regression |
| P2 | Add RFC 3161 timestamp anchoring | Medium | Replaces mock anchor |
