# Contributing

## Development setup

Follow `docs/quickstart/QUICKSTART.md` for required tooling. The normal contributor interfaces are Babashka tasks and the canonical test runner:

```bash
bb test:framework              # focused framework unit coverage
bb test:unit                   # framework + Sew unit coverage
./scripts/test.sh fast         # edit-loop validation gate
./scripts/test.sh all          # broad repository validation gate
```

Use `clojure -M:with-sew` when invoking Clojure directly against the Sew model. See `docs/reference/usage.md` for commands and outputs.

## Before submitting a change

1. Run the narrowest relevant test or scenario first.
2. Run formatting and lint checks when touching Clojure source:
   ```bash
   clojure -M:fmt/check
   clojure -M:lint
   ```
3. Regenerate and review tracked derived files when modifying their source; see `docs/reference/GENERATED_DOCUMENTS.md`.
4. Do not commit unrequested local output under `results/`.
5. Update documentation, schemas, fixtures, and tests when the changed behavior affects them.

## Protocol alignment convention

This framework models both current Solidity behavior and proposed protocol enhancements. Any code, scenario, or documentation modelling a feature not yet in Solidity **must** be marked with:

```clojure
{:protocol/status :protocol/proposed
 :solidity/status :solidity/not-implemented
 :finding/id      "S-DR-NNN"
 :proposal/id     "PRF-PROP-NNN"
 :scenario/kind   :mitigation-validation}
```

See `docs/concepts/protocol-alignment.md` for the status convention and `docs/findings/` for protocol design gaps. Do not represent proposed behavior as deployed Solidity parity.

## Adding or changing scenarios

- Prefer the canonical EDN executable format; JSON remains supported during migration and emits a deprecation warning.
- Register new scenarios or suites through the existing registry conventions, then validate with:
  ```bash
  bb scenario-registry:validate
  ```
- Use `bb run:scenario <scenario-id>` for an end-to-end local replay.
- If a scenario intentionally violates an invariant, declare its expected failure using the existing scenario expectation format. A stale expected failure is itself reported as unused.
- Update generated scenario documentation with `bb docs:scenarios` when changing the S01–S23 summaries.

## Adding a new invariant

1. Define the check function in the appropriate `invariants.clj` file.
2. Register it in the `check-fns` map with a keyword ID.
3. Add the ID to `default-runtime-invariant-ids` in `invariant-catalog.clj`.
4. Add expected-failure entries only to scenarios intended to trigger it.
5. Run `bb test:unit` and the focused scenario coverage.
6. Regenerate golden reports only when the changed expected behavior is intentional:
   ```bash
   bb regenerate-goldens
   ```

## Evidence and generated artifacts

Evidence-oriented tasks may create files under `results/`. Signing requires a private key supplied at invocation time; never add keys or secrets to the repository. See `docs/reference/usage.md`, `schemas/README.md`, and `docs/evidence/` for the artifact contracts.

Generated documentation, golden reports, and trace fixtures have distinct source-of-truth rules. Follow `docs/reference/GENERATED_DOCUMENTS.md` rather than editing generated files directly.

## Pull request scope

Keep changes focused. Include the validation commands you ran and call out intentionally unrun checks, behavioral migrations, protocol-status changes, or regenerated artifacts. Do not mix unrelated formatting churn with behavior changes.
