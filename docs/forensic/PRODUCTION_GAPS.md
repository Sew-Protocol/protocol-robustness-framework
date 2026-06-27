# Production Forensic-Grade Gaps

Current implementation status against requirements for a forensic evidence
runner that could produce externally-reliable output.

## Legend

- ✅ Implemented
- ◐ Partial / needs hardening
- ❌ Not implemented
- — Out of scope (requires external system or new component)

## Sealing & Integrity

| Requirement | Status | Notes |
|---|---|---|
| Atomic file writes (temp → fsync → rename) | ✅ | `write_sealed_json` |
| Readback verification after write | ✅ | SHA-256 recomputed from disk, compared to pre-write hash |
| Self-referential bundle root hash | ✅ | `bundle/hash` excludes itself and `bundle/id` |
| Self-referential overview hash | ✅ | `overview/hash` excludes itself |
| Temp file cleanup on failure | ✅ | `try/finally` across all writes |
| Output directory immutability after run | ❌ | No post-run chmod/chattr to make tree read-only |
| Cryptographic signing of bundle root | ❌ | No Ed25519 or other signature over bundle/hash |
| HMAC with per-run key | ❌ | No per-run signing key |
| Signed chain of custody log | ❌ | No append-only log of runs with signatures |

## Isolation

| Requirement | Status | Notes |
|---|---|---|
| Private tmpfs via unshare | ✅ | `--private-tmpfs` flag, fallback on failure |
| UID check (expected `evidence_runner`) | ✅ | Check 1 — recorded in metadata |
| ptrace_scope ≥ 3 check | ✅ | Check 2 — recorded in metadata |
| /proc accessibility check | ✅ | Check 3 — heuristic |
| Filesystem workspace check | ✅ | Check 4 — `/var/lib/evidence-runner` |
| Root privilege detection | ✅ | Check 5 — recorded in metadata |
| Composite isolation grade | ✅ | `full` → `good` → `partial` → `basic` |
| Dedicated UID per job | ❌ | Single `evidence_runner` user, not per-job |
| Seccomp filter | ❌ | No syscall filtering |
| Capability dropping | ❌ | No `capsh` or similar |
| Read-only root filesystem | ❌ | No `pivot_root` or overlayfs |

## Time & Anchoring

| Requirement | Status | Notes |
|---|---|---|
| Bundle timestamp (wall clock) | ✅ | ISO-8601 in bundle root |
| RFC 3161 timestamp token | ❌ | No TSA integration |
| IPFS/IPLD content-addressed publication | ❌ | — |
| L1 anchoring (event log entry) | ❌ | — |
| Nostr relay publication | ❌ | — |
| Witness cosignature | ❌ | — |

## Verification & Audit

| Requirement | Status | Notes |
|---|---|---|
| File existence checks | ✅ | 6 required files, 4 optional |
| Directory existence checks | ✅ | 4 required directories |
| Bundle root structural validation | ✅ | Schema version, required keys |
| Overview structural validation | ✅ | Required fields |
| Preflight report validation | ✅ | Status check |
| Self-referential hash recomputation | ✅ | Bundle root + overview |
| Cryptographic signature verification | ❌ | No signature to verify |
| Timestamp token verification | ❌ | No TSA token |
| Diff between two runs | ❌ | No comparison tool |
| Multi-runner quorum comparison | ❌ | — |

## Evidence Pipeline

| Requirement | Status | Notes |
|---|---|---|
| `evidence-dag/` directory created | ✅ | Empty — populated by Clojure chain |
| `claims/` directory created | ✅ | Empty — populated by Clojure chain |
| `attestations/` directory created | ✅ | Empty — populated by Clojure chain |
| Scenario execution output captured | ◐ | `run-output.json` written by Clojure pipeline |
| Structured failure summary | ❌ | Only exit code captured, no failure detail |
| Evidence DAG linked to bundle root | ❌ | No hash links from bundle root to DAG entries |

## Run Request & Reproducibility

| Requirement | Status | Notes |
|---|---|---|
| Run request validated preflight | ✅ | EDN/JSON parsing, required fields |
| Registry snapshot captured | ✅ | Copied into output |
| Source snapshot (git commit + dirty) | ✅ | `source-snapshot.json` |
| Environment snapshot (OS, tools) | ✅ | `environment.json` |
| Dependency lock snapshotted | ❌ | No deps.edn or classpath snapshot |
| Input manifest recorded | ✅ | `input-manifest.json` |
| Runner identity recorded | ✅ | From run request |
| Policy versions recorded | ✅ | Evidence + execution policy refs |
| Preflight report preserved | ✅ | `preflight-report.json` |
| Output bundle verifiable from request + snapshot | ❌ | No tool to reproduce run from bundle alone |

## Bundle Distribution

| Requirement | Status | Notes |
|---|---|---|
| Self-contained output directory | ✅ | All artifacts in single tree |
| Portable archive format | ❌ | No tar.gz or similar |
| Export/import tool | ❌ | No `bb forensic:export` / `bb forensic:import` |
| Redaction support | ❌ | No mechanism to sanitize before sharing |
| Size management | ❌ | No compression, dedup, or retention policy |

## Summary by Severity

### Critical gaps (block externally-reliable evidence)

1. **Bundle signing** — `bundle/hash` must be signed with an Ed25519 key
   whose public key is known to verifiers. Without this, anyone can produce
   a bundle claiming any result.

2. **Timestamp anchoring** — An RFC 3161 timestamp token proves the bundle
   existed at a point in time. Without it, a bundle can be backdated or
   its creation window is unbounded.

3. **Post-run immutability** — After completion, the output directory
   should be made read-only (`chmod -R a-w`) and optionally immutable
   (`chattr +i`). Without this, a compromised process after the run can
   still modify output before verification.

4. **Evidence DAG linking** — The bundle root should contain hash links
   to the evidence DAG entries produced by the Clojure pipeline. Without
   this, the bundle root commits to the execution summary but not to the
   detailed per-step evidence.

### Moderate gaps (reduces trust but evidence is still usable)

5. **Multi-runner comparison** — A single runner's output cannot be
   cross-checked. A quorum of 3+ independent runners comparing overview
   hashes would much stronger evidence.

6. **Structured failure report** — Exit code alone doesn't say which
   scenarios failed or why. A structured `failures.json` with per-scenario
   outcome detail would make the bundle self-explanatory.

7. **Reproduce-from-bundle** — Given a bundle, another runner should be
   able to re-execute the same run request against the same registry
   snapshot and confirm the same overview hash.

### Enhancement gaps (important for adoption but not integrity-critical)

8. **Portable export/import** — tar.gz + manifest for external auditors
9. **Dependency snapshot** — pinned deps.edn or classpath listing
10. **Read-only root filesystem** — overlayfs or pivot_root for stronger
    filesystem isolation
11. **Per-run UID** — separate Unix user per run for accountability
12. **Seccomp + capability dropping** — reduce kernel attack surface

## Recommended Next Phase

| Priority | Item | Depends On | Effort |
|---|---|---|---|
| P0 | Sign bundle root with Ed25519 | — | Low (Python + cryptography library, or shell out to Clojure signing) |
| P1 | Post-run chmod a-w + optional chattr +i | — | Trivial (2 lines in run.py after verification) |
| P1 | RFC 3161 timestamp via TSA | — | Medium (HTTP client to TSA, DER encoding of token) |
| P2 | Structured failure summary from run-output.json | — | Medium (parse Clojure output, extract per-scenario results) |
| P2 | Evidence DAG hash links in bundle root | Evidence DAG population | Medium (hash DAG entries, include in bundle root) |
| P3 | bb forensic:export / bb forensic:import | — | Low (tar.gz + manifest) |
| P3 | bb forensic:diff | — | Medium (structural compare of two run dirs) |
| P3 | Quorum comparison tool | Bundle format stable | High (multi-runner orchestration) |
