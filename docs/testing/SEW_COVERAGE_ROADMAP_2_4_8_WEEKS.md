# SEW Coverage Roadmap (2 / 4 / 8 Weeks)

## Goal

Increase evidence quality from scenario-count pass/fail to:

1. transition/guard coverage with explicit unhit backlog closure,
2. adversarial economic safe/unsafe region mapping,
3. stronger multi-trace equilibrium bundles and model↔EVM equivalence coverage.

---

## Success Criteria

- Coverage reports include transition/guard hit maps and outcomes per purpose + threat-tag.
- Release candidates enforce unhit-transition closure policy.
- Adversarial sweeps produce profitability surfaces and promotion candidates.
- Equilibrium results show reduced `:inconclusive` for key claims via evidence bundles.
- High-risk equivalence critical paths pass a minimum gate count.

---

## Milestone: Week 0–2 (Foundation + Transition Coverage)

### Deliverables

- Transition/guard coverage report artifact in CI (`results/test-artifacts/coverage.json`).
- Coverage gate mode in canonical test runner.
- Initial release-candidate threshold on unhit transitions.

### File-level implementation

- `src/resolver_sim/scenario/coverage.clj`
  - CLI entrypoint (`-main`) for artifact generation.
  - Maintain transition/guard frequency maps and unhit transition list.
- `deps.edn`
  - Add `:coverage-report` alias.
- `scripts/test.sh`
  - Add `coverage` mode and coverage gate checks.

### Commands

```bash
clojure -M:coverage-report -- data/fixtures/traces results/test-artifacts/coverage.json
./scripts/test.sh coverage
```

### CI gates (Week-2)

- `unhit-transitions <= 4` (configurable via `MAX_UNHIT_TRANSITIONS`).
- Coverage artifact required in build outputs.
- Required transition categories present at least once:
  `creation`, `state-change`, `escalation`, `resolution`, `timeout`, `governance`, `economic`.

---

## Milestone: Week 2–4 (Adversarial Economic Surfaces)

### Deliverables

- Parameterized profitability surface artifacts:
  - `surface.csv`, `surface.json`, `regions.json`, `promotions.json`.
- Gate mode for adversarial sweep artifacts.
- Promotion queue for deterministic regression onboarding.

### File-level implementation

- `python/adversarial_profitability_sweep.py`
  - Maintain parameter sweeps over fee/latency/capacity/escalation budget.
  - Persist safe/unsafe regions and top promotion candidates.
- `scripts/test.sh`
  - Add `adversarial-sweep` and `adversarial-gates` modes.
- `data/fixtures/traces/regression/` (rolling updates)
  - Add promoted top-risk deterministic fixtures.

### Commands

```bash
./scripts/test.sh adversarial-sweep
./scripts/test.sh adversarial-gates
```

### CI gates (Week-4)

- Required sweep artifacts exist for latest run.
- Promotion list contains at least 3 candidates.
- Unsafe-region growth comparison against baseline snapshot (recommended in CI stateful store):
  - target threshold: `<= +10%` per family.

---

## Milestone: Week 4–8 (Equilibrium Evidence + Equivalence Expansion)

### Deliverables

- Deviation/counterfactual evidence bundles for equilibrium claims.
- Expanded critical-path model↔EVM equivalence traces (escalation, timeout, settlement races).
- Release gates for minimum bundle/equivalence pass counts.

### File-level implementation

- `src/resolver_sim/scenario/equilibrium.clj`
  - Bundle-aware validators and evidence strength tagging.
- `src/resolver_sim/scenario/theory.clj`
  - Theory evaluation integration for bundle references.
- `src/resolver_sim/scenario/projection.clj`
  - Add comparison fields needed for deviation evidence.
- `src/resolver_sim/protocols/sew/diff.clj`
  - Harden projection and hashing for high-risk path comparisons.
- `data/fixtures/suites/equilibrium-validation.edn`
- `data/fixtures/suites/spe-validation.edn`
  - Minimum evidence-bundle sets.
- `docs/trace-end-equilibrium-validation.md`
  - Update semantics and gating recommendations.

### Commands

```bash
./scripts/test.sh suites
./scripts/test.sh contracts
# In protocol repo CI job (if checked out):
forge test --match-test test_trace_equivalence
```

### CI gates (Week-8)

- Equilibrium evidence bundles passed: `>= 12` (recommended baseline).
- `:inconclusive` rate for critical claims: `< 25%`.
- Equivalence critical paths passed: `>= 10`.
- RC strict closure: `unhit-transitions == 0`.

---

## Canonical CI Job Sequence

1. `./scripts/test.sh all`
2. `./scripts/test.sh coverage`
3. `./scripts/test.sh adversarial-sweep`
4. `./scripts/test.sh adversarial-gates`
5. `./scripts/test.sh suites`
6. Optional cross-repo equivalence: `forge test --match-test test_trace_equivalence`

Implemented workflow: `.github/workflows/sew-validation-gates.yml`
with three jobs:

- `core-validation`
- `coverage-gates`
- `adversarial-surfaces`

---

## Notes

- Keep Clojure as canonical semantics owner for scenario/protocol logic.
- Python remains orchestration and exploration layer for adversarial search.
- Single-trace equilibrium remains proxy evidence unless bundle data is present.
