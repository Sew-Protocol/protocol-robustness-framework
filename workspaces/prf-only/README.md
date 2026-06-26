# PRF-Only Workspace

Framework-only development view. Exposes the protocol-agnostic framework
core without protocol implementation noise.

## What's included

- `src/` — framework core (registries, canonical hashing, evidence DAG,
  attestation engine, execution runners, validation root, artifact schemas)
- `test/` — all tests (including protocol tests, but focus is framework)
- `docs/` — specification and design documents

## What's excluded

- `protocols_src/` — protocol implementations (Sew, Yield, etc.)
- Protocol-specific scenarios
- Forensic evidence outputs
- Private discovery material
- Sealed logs

## Usage

From this directory:

    clojure -M            # framework-only REPL
    clojure -M:test       # framework-only tests

For the full stack (framework + protocols), use:

    clojure -M:with-sew   # from project root
    # or
    cd ../with-sew && clojure -M

## When to use this

Work on:

- Hash intent registry
- Claim definition registry
- Attestor registry
- Canonical hash functions
- Evidence DAG operations
- Attestation engine
- Execution runner definitions
- Validation root
- Artifact schema definitions
- Evidence policies

Do NOT use this workspace for:

- Protocol-specific scenario development (use `with-sew/`)
- Forensic evidence production (use `forensic-runner/` template)
- Private discovery (use `~/prf-private/`)
