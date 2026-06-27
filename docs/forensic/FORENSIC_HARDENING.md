# FORENSIC_HARDENING.md

## 1. Purpose

Document the isolation architecture, runtime checks, sealing pipeline, and
grade model that harden forensic run output against tampering, concurrent
modification, and privilege escalation.

This is not a spec. It describes the implemented defense layers and how they
compose.

## 2. Threat Model

The forensic runner assumes the kernel is trusted. Within that boundary, the
following threats are addressed:

| Threat | Mitigation |
|---|---|
| Another process modifies in-flight output before hashing | Atomic seal — hash before write, temp→fsync→rename, readback verify |
| Another process tampers output after run completes | Self-referential SHA-256 in bundle root and overview; verification recomputes and compares |
| Another process reads in-flight output via /proc/\<pid\>/fd | ptrace_scope >= 3, dedicated UID |
| Another process running as same UID writes to workspace | Private tmpfs per run |
| Process escapes user isolation | UID check verifies dedicated `evidence_runner` user |
| Output leaked via shared /proc filesystem | mount namespace isolation |
| Root escalation | Runtime root detection check |

## 3. Sealing Pipeline

Every forensic run artifact passes through this write path:

```
serialize → hash → temp → fsync → rename → readback → verify
```

Implemented in `scripts/forensic/preflight.py::write_sealed_json`:

1. **Serialize** — JSON with `indent=2, sort_keys=True` for deterministic output
2. **Hash** — SHA-256 of the serialized bytes, computed in memory before touching disk
3. **Temp** — written to a temporary file in the same directory (same filesystem → atomic rename)
4. **Fsync** — `f.flush()` + `os.fsync(f.fileno())` — ensures data is on disk before rename
5. **Rename** — `os.replace()` — atomic on POSIX; no intermediate state is visible
6. **Readback** — file is read back from disk immediately after rename
7. **Verify** — SHA-256 recomputed from readback bytes, compared against pre-write hash
8. **Failure** — `IOError` raised on hash mismatch; temp file cleaned up

If any step fails, the target file is never created (temp file cleaned up,
rename never occurred).

## 4. Self-Referential Hashing

Two artifacts carry self-referential hashes that commit to their entire
content:

### `run-bundle-root.json`

```json
{
  "bundle/schema-version": "bundle-root.v1",
  "bundle/id": "<sha256>",
  "bundle/hash": "<sha256>",
  ...
}
```

- `bundle/id` and `bundle/hash` are excluded from the canonical bytes
- SHA-256 is computed over all other fields
- Both `bundle/id` and `bundle/hash` are set to this hash
- Any modification to any other field changes the hash (detectable on verify)

### `run-overview.json`

```json
{
  "overview/schema-version": "run-overview.v1",
  "overview/hash": "<sha256>",
  ...
}
```

Same pattern: `overview/hash` excluded from canonical bytes, SHA-256
computed over the rest.

## 5. Filesystem Isolation

Two modes, controlled by `--private-tmpfs` flag:

### Shared Filesystem (default)

Output written directly to `~/prf-runs/<run-id>/` on the host filesystem.
No additional isolation beyond standard filesystem permissions.

### Private tmpfs

Wraps the entire run in a private mount namespace:

```
mkdir temp workspace       → mktemp -d
mount tmpfs                → unshare --user --map-root-user -m mount -t tmpfs tmpfs <ws>
run inside tmpfs            → python3 run.py --output-base <ws>
copy to ~/prf-runs/        → cp -a <ws>/. ~/prf-runs/<run-id>/
cleanup                    → rm -rf <ws>
```

Files written to the tmpfs are invisible to any process outside the mount
namespace. After completion, the output tree is copied to the durable
archive location, then the tmpfs is unmounted and discarded.

Fallback: if `unshare` or `mount` fails (missing user namespace support),
a warning is logged and execution continues with shared filesystem. The
metadata records the actual mode used.

## 6. OS-Level Isolation Checks

Five checks run during every forensic execution. Results are recorded in
the run metadata. Checks never block execution — they inform the grade.

### Check 1: UID (`check_uid`)

Verifies the process is running under the expected dedicated user account.

- Expected: `evidence_runner`
- Source: `os.geteuid()` + `pwd.getpwuid()`
- Fail: process running as wrong user or root

### Check 2: ptrace Scope (`check_ptrace_scope`)

Verifies YAMA ptrace scope >= 3, which disables `ptrace` from non-parent
processes (prevents `/proc/<pid>/mem` and register injection attacks).

- Expected: 3
- Source: `/proc/sys/kernel/yama/ptrace_scope`
- Skip: file not found (YAMA LSM not enabled)
- Fail: value < 3

### Check 3: /proc Isolation (`check_proc_isolation`)

Heuristic probe: verifies `/proc/<pid>/status` is accessible and contains
expected fields. Does not prove full isolation but detects basic /proc
failures.

- Expected: `Name` field present in `/proc/<pid>/status`
- Fail: cannot read own /proc entry or unexpected content

### Check 4: Filesystem Access (`check_fs_access`)

Verifies write access to the expected isolated workspace directory.

- Expected: write access to `/var/lib/evidence-runner`
- Skip: directory does not exist
- Fail: directory exists but not writable

### Check 5: Privileges (`check_privileges`)

Verifies the process is not running as root.

- Expected: uid != 0
- Fail: uid == 0

## 7. Isolation Grade

The grade is a single keyword computed from the isolation mode and check
results:

| Mode | All Checks Pass | Any Check Fails |
|---|---|---|
| `private-tmpfs` | `full` | `good` |
| `shared-filesystem` | `partial` | `basic` |
| unknown | `unknown` | `unknown` |

Semantics:

- **full** — Maximum practical isolation on commodity Linux. Private tmpfs
  mount namespace + all OS hardening checks pass. Tampering requires kernel-
  level access.
- **good** — Private tmpfs active but some OS checks failed. Evidence is
  isolated from filesystem peers but host-level hardening is incomplete.
- **partial** — Shared filesystem but all OS checks pass. Process isolation
  is sound but evidence is on the shared filesystem during execution.
- **basic** — Shared filesystem and some OS checks failed. Minimum isolation.
- **unknown** — Cannot determine (unexpected isolation mode).

## 8. Metadata Schema

Isolation metadata appears in both `run-overview.json` and
`run-bundle-root.json` under these keys:

```json
{
  "isolation/mode": "shared-filesystem | private-tmpfs",
  "isolation/grade": "full | good | partial | basic | unknown",
  "isolation/all-pass": true | false,
  "isolation/checks": [
    {"check": "uid",          "status": "pass", "detail": "...", "expected": "evidence_runner", "actual": "evidence_runner"},
    {"check": "ptrace-scope", "status": "pass", "detail": "...", "expected": 3,               "actual": 3},
    {"check": "proc-isolation", "status": "pass", "detail": "...", "expected": true,           "actual": true},
    {"check": "fs-access",    "status": "pass", "detail": "...", "expected": true,             "actual": true},
    {"check": "privileges",   "status": "pass", "detail": "...", "expected": "non-root",       "actual": 1001}
  ]
}
```

## 9. Verification

`bb forensic:verify <run-dir>` checks:

1. File existence (6 required files)
2. Directory existence (4 required directories)
3. Bundle root structural validity (schema version, required keys)
4. Overview structural validity (required fields)
5. Preflight report validity (status check)
6. **Bundle root hash recomputation** — SHA-256 of canonical fields
   compared against recorded `bundle/hash`
7. **Overview hash recomputation** — SHA-256 of canonical fields compared
   against recorded `overview/hash`

Checks 6 and 7 detect post-run tampering: any modification to a field
covered by the self-referential hash will cause a mismatch.

## 10. Bundle Signing

### Flow

1. Canonical bundle dict built (excluding `bundle/id`, `bundle/hash`,
   `bundle/signature`, `bundle/signing-key-id`)
2. SHA-256 computed → `bundle/hash`; `bundle/id` = `bundle/hash`
3. If `--sign <key-path>` provided: shells out to Clojure's
   `resolver-sim.benchmark.signing/sign-hash` to sign the hash with Ed25519
4. Hex signature stored in `bundle/signature`; key identifier in
   `bundle/signing-key-id`
5. Bundle sealed via `write_sealed_json` (signature fields excluded from
   self-referential hash but included in file-level seal)

### Key generation

    openssl genpkey -algorithm ed25519 -out signing_key.pem
    openssl pkey -in signing_key.pem -pubout -out signing_key.pub.pem

Supports PKCS#8 PEM and OpenSSH formats (via Clojure buddy-core).

### Verification

    bb forensic:verify <run-dir> --public-key signing_key.pub.pem

Without `--public-key`, verification reports the bundle is signed but
skips cryptographic verification.

## 11. Post-Run Output Hardening

After verification, the output tree is hardened:

```
chmod -R a-w <run-dir>       # remove write permission
chattr -R +i <run-dir>       # immutable flag (best-effort)
```

Suppress with `--no-harden`.

## 12. Script Reference

To produce `isolation/grade: full` forensic runs:

| Requirement | How |
|---|---|
| Private tmpfs | `bb forensic:run --private-tmpfs` |
| Dedicated user | Create `evidence_runner` system user, run forensic jobs under it |
| ptrace_scope >= 3 | Set `kernel.yama.ptrace_scope = 3` in `/etc/sysctl.d/` |
| Isolated workspace | Create `/var/lib/evidence-runner/` writable by `evidence_runner` |
| Non-root | Do not use `sudo` or root UID to run forensic jobs |
| /proc functioning | Default Linux behavior |

## 11. Script Reference

| File | Purpose |
|---|---|
| `scripts/forensic/preflight.py` | Preflight checks, `write_sealed_json`, `compute_sha256` |
| `scripts/forensic/run.py` | Forensic run orchestrator |
| `scripts/forensic/run.sh` | Shell wrapper (handles `--private-tmpfs` via unshare) |
| `scripts/forensic/verify.py` | Run bundle verification |
| `scripts/forensic/isolation_checks.py` | 5 OS-level isolation checks |
