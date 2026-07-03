# Commands

Registered review-gate commands and their current backstop tier.

## Fast

- `bb commands:validate` — validate command registry, bb task surface, and command docs consistency.
- `bb fmt:check` — check formatting without rewriting files.
- `bb lint` — lint source and test code with clj-kondo.
- `bb test:fast` — run the edit-loop-safe fast test target.

## Default

- `bb test` — run the canonical project test gate.
- `bb concepts:validate` — validate concept data files and the concept registry.
- `bb scenarios:validate` — validate the invariant scenario registry and registered scenario suites.
- `bb benchmark:validate` — validate benchmark pack definitions and referenced resources.
- `bb artifact-registry:validate` — validate the default artifact registry if present.
- `bb replay:determinism --suite suite/reference-validation-v1 --runs 2` — run a two-pass deterministic replay self-test for the reference validation suite.

## Manual

- `bb validate` — run the structural validation pipeline including notebook checks.
- `bb backstop:fast` — run the fast registered backstop commands.
- `bb backstop` — run the default registered backstop commands.
