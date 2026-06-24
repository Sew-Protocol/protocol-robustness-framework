# Project Context

## Project Name

Protocol Robustness Framework

## Purpose

This repository is a Clojure/Babashka simulation and validation framework for
cryptoeconomic protocols. It models protocol behavior as deterministic state
transitions, replays multi-step scenarios, checks invariants, and emits evidence,
registry, benchmark, notebook, and validation artifacts.

Sew is the current primary benchmark protocol. Keep framework-level machinery
separate from Sew-specific lifecycle, economics, actions, invariants, and
scenario assumptions.

## Framework-Level Components

- `resolver-sim.core` and `resolver-sim.core.cli` own the main simulation CLI.
- `resolver-sim.protocols.protocol` defines protocol adapter contracts.
- `resolver-sim.protocols.registry` registers protocol implementations.
- `resolver-sim.scenario.*` owns scenario loading, expectations, projection,
  theory/equilibrium evaluation, coverage, reports, and suites.
- `resolver-sim.sim.*` owns generic simulation, sweeps, reference validation,
  adversarial trials, fixtures, trace scoring, and reporting.
- `resolver-sim.io.*` owns scenario/trace serialization, diffing, audit output,
  invariant runners, telemetry emission, and trace stores.
- `resolver-sim.hash.canonical` is the authoritative canonical hash engine.
- `resolver-sim.evidence.*` owns evidence capture, chain/registry helpers,
  timestamping, semantic facts, and forensic adapters.
- `resolver-sim.validation.*` owns validation-root and artifact-registry
  validation adapters.
- `resolver-sim.yield.*` owns reusable yield models, partial fills, accounting,
  liquidity, risk, loss, exact math, and yield modules.
- `resolver-sim.financial.*` owns generic financial finality, solvency, loss, and
  pro-rata characterization helpers.
- `resolver-sim.time.*` owns temporal context, deadlines, and temporal invariant
  helpers.
- `resolver-sim.benchmark.*` owns benchmark manifests, evidence bundles,
  signing, sharing, hashing compatibility, and CLI commands.
- `resolver-sim.notebook.*` and `resolver-sim.notebooks.*` provide reusable Clerk
  views, checks, styles, manifests, and notebook support.

## Sew-Specific Protocol Components

- `resolver-sim.protocols.sew` is the Sew protocol adapter entry point.
- `resolver-sim.protocols.sew.actions` owns Sew action dispatch.
- `resolver-sim.protocols.sew.lifecycle` owns lifecycle transitions.
- `resolver-sim.protocols.sew.state-machine` owns Sew transition/state-machine
  behavior.
- `resolver-sim.protocols.sew.registry` owns Sew resolver/escrow registry logic.
- `resolver-sim.protocols.sew.accounting` owns Sew accounting behavior.
- `resolver-sim.protocols.sew.resolution` owns dispute resolution behavior.
- `resolver-sim.protocols.sew.economics` owns Sew-specific economics, including
  slash allocation policy.
- `resolver-sim.protocols.sew.invariants` and
  `resolver-sim.protocols.sew.invariants.*` own Sew invariant composition and
  domain-specific invariant groups.
- `resolver-sim.protocols.sew.invariant-scenarios.*` owns deterministic Sew
  invariant scenarios.
- `resolver-sim.protocols.sew.invariant-runner` owns the Sew deterministic
  invariant runner.
- `resolver-sim.protocols.sew.projection` owns the Sew terminal trace projection.
- `resolver-sim.protocols.sew.yield.*` owns Sew-specific yield policy and
  invariant integration.
- `resolver-sim.protocols.sew.io.trace-export` owns Sew trace export.

## Generated Artifacts

- `results/` contains run outputs, evidence, per-run artifacts, and transient
  validation output. Do not commit new generated run output unless requested.
- `results/test-artifacts/` contains current test-artifact registry output.
- `data/fixtures/golden/` contains pinned golden scenario reports.
- `data/fixtures/traces/` contains invariant trace fixtures.
- `scenarios/` contains public JSON scenario fixtures generated from or aligned
  with Clojure scenario definitions.
- `docs/generated/` contains generated catalogs and contracts.
- `docs/overview/*_GENERATED.md` and `docs/protocols/sew/*_GENERATED.md` are
  generated state-machine/coverage docs.
- `public/build/` is Clerk static output.
- `resources/test-vectors/canonical-hash-v1/` and
  `resources/test-vectors/pro-rata/` contain committed conformance/parity
  fixtures.

## Development Tooling

- `bb.edn` is the command surface for tests, validation, notebooks, evidence,
  benchmark, and replay tasks.
- `deps.edn` defines Clojure dependencies and aliases.
- `dev/user.clj` and `dev/dev/*.clj` support REPL workflows.
- `scripts/` contains validation, fixture, docs, artifact, scenario, and CI
  helpers.
- `python/` contains Python support tools and tests.
- `test/foundry/` contains Solidity/Foundry tests.
- `.clj-kondo/` contains lint config/import cache.
- `.clojure-mcp/` contains ClojureMCP config.

## Specs And Architecture Docs

Confirmed files under `docs/specs`:

- `docs/specs/ATTESTATION_SPEC_V1.md`
- `docs/specs/ATTESTOR_REGISTRY_SPEC_V1.md`
- `docs/specs/CLAIM_DEFINITION_REGISTRY_SPEC_V1.md`
- `docs/specs/INTENT_DSL_SPEC_V1.md`
- `docs/specs/INTENT_REGISTRY_SPEC_V1.md`
- `docs/specs/PROJECTION_ARTIFACT_SPEC_V1.md`
- `docs/specs/PROJECTION_DEFINITION_REGISTRY_SPEC_V1.md`
- `docs/specs/evidence/CANONICAL_HASH_SPEC_V1.md`
- `docs/specs/evidence/CANONICAL_HASH_SPEC_V1_BINARY_ENCODING_ABI.md`
- `docs/specs/evidence/CANONICAL_HASH_SPEC_V1_CONFORMANCE_TEST_VECTOR.md`
- `docs/specs/evidence/EVIDENCE_COMMITMENT_SPEC_V1.md`
- `docs/specs/evidence/EVIDENCE_NODE_SPEC_V1.md`
- `docs/specs/evidence/EVIDENCE_POLICY_SPEC_V1.md`
- `docs/specs/evidence/IDENTITY_ALGEBRA_SPEC_V1.md`
- `docs/specs/evidence/INTENT_REGISTRY_SPEC_V1.md`
- `docs/specs/execution/EXECUTION_REGISTRY_SPEC_V1.md`

Important architecture docs:

- `docs/architecture/ARCHITECTURE.md`
- `docs/architecture/EVIDENCE_REGISTRY.md`
- `docs/architecture/EVIDENCE_CHAIN_REMEDIATION.md`
- `docs/architecture/YIELD_AND_SNAPSHOT_MODULES.md`
- `docs/architecture/YIELD_V1_VAULT_CENTRIC_TESTING.md`
- `docs/architecture/GENERIC_ECONOMICS_LAYERING.md`
- `docs/framework-boundaries.md`
- `docs/interface-contract.md`
- `docs/protocol-alignment.md`
- `docs/protocol/temporal-rules.md`
- `docs/pro_rata_proportional_math_spec.md`
- `docs/pro_rata_test_vectors.md`
- `docs/REPL_GUIDE.md`
- `docs/notebooks/README.md`

## Important Directories

- `src/resolver_sim/`: framework and protocol source.
- `src/resolver_sim/protocols/sew/`: Sew protocol implementation.
- `src/resolver_sim/protocols/sew/invariants/`: Sew invariant groups.
- `src/resolver_sim/protocols/sew/invariant_scenarios/`: Sew deterministic
  invariant scenario sources.
- `test/resolver_sim/`: Clojure test suites grouped by subsystem.
- `notebooks/`: top-level Clerk notebooks.
- `src/resolver_sim/notebooks/`: notebook support and reusable notebook modules.
- `docs/`: human docs, specs, architecture notes, generated catalogs.
- `docs/specs/`: current protocol/framework specification documents.
- `data/fixtures/`: fixture inputs, traces, golden reports, actors, tokens,
  suites, and thresholds.
- `scenarios/`: public JSON scenarios.
- `suites/`: reference validation and domain reference suites.
- `resources/test-vectors/`: committed canonical hash and pro-rata test vectors.
- `scripts/`: project automation and validation scripts.
- `results/`: generated run output; normally not hand-edited.
- `demos/`: demo specs and generated demo artifacts.

## Current State By Topic

- Canonical hashing: implemented in `resolver-sim.hash.canonical` with typed
  canonical bytes, domain-separated hashes, `hash-with-intent`, registered
  `hash-intents`, and conformance tests under `test/resolver_sim/hash/`.
  `resolver-sim.benchmark.hashing` is deprecated compatibility code.
- Intent DSL / registry: spec files exist under `docs/specs`; implementation
  currently uses the in-code `hash-intents` registry and `validate-registry!` in
  `resolver-sim.hash.canonical`. `resolver-sim.definitions.registry` is a
  semantic definitions registry for replay/report/evidence/Clerk labels and
  transition metadata, not the hash intent registry.
- Projections: generic helpers live in `resolver-sim.scenario.projection`; Sew
  terminal projection lives in `resolver-sim.protocols.sew.projection`.
  Projection evidence intent exists in `resolver-sim.hash.canonical`.
- Evidence chain: `resolver-sim.evidence.chain` provides run-scoped registry and
  chain cursor state, chain fields, registry building, registry persistence, and
  artifact registry integration.
- Artifact registry: `test-artifacts.json` style registries are validated by
  `resolver-sim.validation.integration.artifact-registry` and
  `bb validation:artifact-registry`; dependency matching is currently by
  artifact `schema_version`, with known non-artifact schemas hardcoded.
- TSA / attestation: `resolver-sim.evidence.timestamping` supports local
  self-signed timestamp proofs and RFC 3161 TSA requests. `bb manifest:sign`,
  `bb evidence:sign`, and benchmark attestation/verification tasks exist.
- Pro-rata: reusable pro-rata/vector emitters live in
  `resolver-sim.test-vectors.pro-rata`; generic financial tests live under
  `test/resolver_sim/financial/`; Sew slash allocation remains in
  `resolver-sim.protocols.sew.economics`.
- Yield shortfall: reusable yield/shortfall logic lives in
  `resolver-sim.yield.*`; Sew policy integration lives in
  `resolver-sim.protocols.sew.yield.*`; notebooks include yield shortfall and
  scenario workbenches.
- Temporal context: `resolver-sim.time.context` provides canonical
  `:context/time` with legacy `:block-time` compatibility. Invariants should use
  accessors rather than broad root-key rewrites.
- Clerk notebooks: top-level notebooks live in `notebooks/`; the Clerk server is
  started by `bb notebook` / `clojure -M -m notebooks.serve`; notebook validation
  tasks are `bb notebook:check`, `bb notebook:lint`, and `bb notebook:ci`.
- REPL/dev tooling: `bb repl`, `bb repl:light`, `bb repl:check`, and
  `clojure -M:repl/nrepl` are available. Use `clj-nrepl-eval --discover-ports`
  and require changed namespaces with `:reload`.

## Commands

Concrete commands present in `bb.edn`:

- Format Clojure source/test: `bb fmt`
- Lint source/test: `bb lint`
- Structural validation: `bb validate`
- All unit target: `bb test:unit`
- Framework-only unit tests: `bb test:framework`
- Sew protocol unit tests: `bb test:sew`
- Yield unit tests: `bb test:yield`
- Generator/property tests: `bb test:generators`
- Cross-layer contract tests: `bb test:contracts`
- Deterministic invariant tests: `bb test:invariants`
- Fixture suites / scenario replay suites: `bb test:suites`
- Full canonical gate: `bb test`
- Single scenario replay: `bb run:scenario <alias|scenario-path>`
- Scenario family replay: `bb run:scenario:family <selector>`
- Artifact registry validation:
  `bb validation:artifact-registry [path/to/test-artifacts.json]`
- Evidence registry build: `bb evidence:registry [--dir <run-dir>]`
- Evidence build: `bb evidence:build --scenario <alias|scenario-path>`
- Notebook server: `bb notebook`
- Notebook namespace/data-shape validation: `bb notebook:check`
- Notebook lint: `bb notebook:lint`
- Notebook CI: `bb notebook:ci`
- Notebook static build task: `bb clerk-build`
- Fixture sync after scenario-source edits: `bb fixtures:sync`
- Scenario docs regeneration: `bb docs:scenarios`
- REPL compile smoke: `bb repl:check`

Command discrepancy to preserve:

- `bb validate` is documented in `bb.edn` as "Structural validation pipeline:
  lint (fmt skipped due to env artifact issues)" and runs `clj -M:lint`; it does
  not run `bb fmt`.
- `bb clerk-build` exists but calls `clojure -M:build-clerk`; no
  `:build-clerk` alias was found in `deps.edn`. TODO(agent): confirm or repair
  the notebook static build command before relying on it.

## Do Not Break

- Keep framework-level code free of Sew-specific assumptions.
- Keep Sew-specific behavior under `resolver-sim.protocols.sew.*` unless a
  reusable abstraction already exists.
- Preserve deterministic replay: no hidden randomness, ambient time, unstable map
  ordering, random UUIDs, or non-canonical serialization in identity/evidence
  paths.
- Use `resolver-sim.hash.canonical` for new hashes; do not add callers to
  deprecated `resolver-sim.benchmark.hashing`.
- Preserve hash intent boundaries and increment intent versions when projection
  semantics change.
- Preserve evidence chain fields, run-scoped cursor isolation, and artifact
  registry linkage.
- Preserve fixture/source parity between Sew invariant scenario sources,
  `data/fixtures/traces/`, and public `scenarios/`.
- Do not update golden reports, generated docs, public scenarios, test vectors,
  or `results/` artifacts unless the semantic change is understood and requested.
- Use temporal accessors in `resolver-sim.time.context`; avoid broad
  `:block-time` to `Instant` rewrites.
- Keep yield module behavior reusable; put Sew policy choices under Sew
  namespaces.
- Keep notebooks executable, thin, deterministic, and validated.

## Before Making Changes

- Read the relevant namespace, tests, and docs/specs first.
- Decide whether the change is framework-level, Sew-specific, generated output,
  tooling, or docs-only.
- For Clojure edits, use ClojureMCP structural tools where practical.
- Identify affected identity, projection, evidence, temporal, replay, or fixture
  domains before editing.
- Prefer one coherent edit at a time; stop and fix the first failed reload, lint,
  or test.
- For scenario-source edits, plan fixture sync and docs regeneration.
- For notebook edits, plan `bb notebook:ci` plus any scenario/invariant test that
  supports the demonstrated behavior.
- Update `CHANGELOG.md` after completing work.

## Before Finishing

For Clojure source edits:

```bash
bb fmt
bb lint
bb validate
```

Then run the narrow relevant test target:

```bash
bb test:framework
bb test:sew
bb test:yield
bb test:invariants
bb test:suites
```

For scenario replay work:

```bash
bb run:scenario <alias|scenario-path>
bb validation:artifact-registry
```

For artifact/evidence work:

```bash
bb evidence:registry
bb validation:artifact-registry
```

For notebook work:

```bash
bb notebook:ci
bb clerk-build
```

TODO(agent): `bb clerk-build` may be misconfigured because `deps.edn` currently
does not define `:build-clerk`.

For docs-only work:

```bash
bb validate
```

Report changed files, Clojure edit tools used, REPL evaluations, validation
commands, skipped checks, failures, discrepancies, and remaining uncertainty.

