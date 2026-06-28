#!/usr/bin/env bash
# Canonical test runner for Protocol Robustness Framework.
#
# Usage:
#   ./scripts/test.sh            # run all suites (unit + invariants + fixtures + triage)
#   ./scripts/test.sh unit       # Clojure unit tests only
#   ./scripts/test.sh generators # Generator + equilibrium regression tests (pinned seeds)
#   ./scripts/test.sh invariants # S01–S100 deterministic invariant scenarios only
#   ./scripts/test.sh yield-provider-scenarios # Standalone yield-v1 file-backed scenarios
#   ./scripts/test.sh sew-yield-scenarios      # Sew escrow + yield integration file-backed scenarios
#   ./scripts/test.sh yield-scenarios          # Compatibility alias: runs both yield path suites (deprecated)
#   ./scripts/test.sh lint:cleanup          # Clean up lint warnings (interactive)
#   ./scripts/test.sh contracts  # Cross-layer contract checks (proto/service/wire compatibility)
#   ./scripts/test.sh suites     # fixture suite runner (all-invariants + equilibrium-validation + spe-validation + spe-regression)
#   ./scripts/test.sh reference-validation  # public reference evidence harness (suites/reference-validation-v1)
#   ./scripts/test.sh triage     # Failure triage grouped by purpose/threat-tag
#   ./scripts/test.sh equivalence-new # New equivalence comparison stack (auth/race/escalation/accounting)
#   ./scripts/test.sh monte-carlo # Representative Monte Carlo phase sweep (5 domains)
#   ./scripts/test.sh long-horizon # Extended horizon scenarios (100/200/500/1000 epochs)
#
# Evidence tiers (see core.phases/phase-evidence-tiers):
#   :protocol-kernel-evidence — calls resolve-dispute or replay-with-protocol
#   :analytic                 — algebraic/closed-form, no protocol calls
#   :exploratory              — narrative, qualitative, or pre-prototype
#
# Phases C, E, M (analytic) are NOT CI-gated — run via --phase-c-dr etc.
# Phases Q, R, U, V, W, X (exploratory) are NOT CI-gated — run via --phase-q etc.
# All other phases are documented in core.phases/phase-evidence-tiers.
#
# Exit code: 0 = all passed, 1 = any failure.

cd "$(dirname "$0")/.."
source "$(dirname "$0")/monte_carlo.sh"

MODE="${1:-all}"
FAILURES=0
ARTIFACT_DIR="$(python3 -c "from scripts.evidence_config import EvidenceConfig; print(EvidenceConfig().artifact_dir)" 2>/dev/null)" || ARTIFACT_DIR="results/test-artifacts"
ARTIFACT_FILE="$ARTIFACT_DIR/test-summary.json"
RUN_MANIFEST_FILE="$ARTIFACT_DIR/test-run.json"
ARTIFACT_REGISTRY_FILE="$ARTIFACT_DIR/test-artifacts.json"
CLAIMABLE_CLASSIFICATION_FILE="$ARTIFACT_DIR/claimable-classification.json"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
MAX_UNHIT_TRANSITIONS="${MAX_UNHIT_TRANSITIONS:-4}"
MAX_UNSAFE_REGION_DELTA_PCT="${MAX_UNSAFE_REGION_DELTA_PCT:-10}"
STRICT_CLAIM_REGISTRY="${STRICT_CLAIM_REGISTRY:-0}"

mkdir -p "$ARTIFACT_DIR"

require_clojure() {
  if ! command -v clojure >/dev/null 2>&1; then
    echo "ERROR: Clojure CLI not found on PATH."
    echo "Install Clojure CLI, then retry."
    echo "Hint: this is installed automatically in CI via setup-clojure."
    return 127
  fi
  return 0
}

TARGET_LOG=""

start_target() {
  TARGET_LOG="$ARTIFACT_DIR/.target-${1}-${RUN_ID}.log"
  : > "$TARGET_LOG"
}

record_target() {
  target="$1"
  code="$2"
  dur_ms="$3"
  status="pass"
  if [ "$code" -ne 0 ]; then
    status="fail"
  fi
  printf '%s,%s,%s,%s,%s\n' "$target" "$status" "$code" "$dur_ms" "$TARGET_LOG" >> "$ARTIFACT_DIR/.targets-${RUN_ID}.csv"
}

run_target() {
  target="$1"
  func="$2"
  start_target "$target"
  t0="$(date +%s)"
  "$func" >"$TARGET_LOG" 2>&1
  code=$?
  t1="$(date +%s)"
  dur_ms=$(( (t1 - t0) * 1000 ))
  record_target "$target" "$code" "$dur_ms"
  cat "$TARGET_LOG"
  return "$code"
}

run_unit() {
  require_clojure || return $?
  echo "Running unit tests (all — framework + Sew)..."
  clojure -M:test:with-sew -e "
(require '[clojure.test :as t])
(require '[resolver-sim.core-tests])
(require '[resolver-sim.protocol-alignment-test])
(require '[resolver-sim.protocols.sew.replay-test])
(require '[resolver-sim.protocols.sew.forking-strategist-expectations-test])
(require '[resolver-sim.scenario.expectations-test])
(require '[resolver-sim.scenario.equilibrium-test])
(require '[resolver-sim.sim.multi-epoch-test])
(require '[resolver-sim.sim.defection-test])
(require '[resolver-sim.sim.strategy-adaptation-test])
(require '[resolver-sim.protocols.sew.slashing-test])
(require '[resolver-sim.protocols.sew.phase-k-test])
(require '[resolver-sim.protocols.sew.phase-m-test])
(require '[resolver-sim.sim.waterfall-test])
(require '[resolver-sim.io.scenario-fixture-parity-test])
(require '[resolver-sim.contract-model.replay-batch-test])
(require '[resolver-sim.contract-model.replay-batch-sew-test])
(require '[resolver-sim.contract-model.replay-batch-appeal-test])
(require '[resolver-sim.contract-model.replay-batch-slash-domain-test])
(require '[resolver-sim.protocols.sew.dispute-resolution-coverage-test])
(require '[resolver-sim.financial.pro-rata-characterization-test])
(require '[resolver-sim.scenario.suites-test])
(require '[resolver-sim.validation.scenario-registry-test])
(require '[resolver-sim.run.overview-test])
(require '[resolver-sim.evidence.node-test])
(require '[resolver-sim.evidence.attestation-dag-test])
(let [results (t/run-tests
    'resolver-sim.core-tests
    'resolver-sim.protocol-alignment-test
    'resolver-sim.protocols.sew.replay-test
    'resolver-sim.protocols.sew.forking-strategist-expectations-test
    'resolver-sim.protocols.sew.slashing-test
    'resolver-sim.protocols.sew.phase-k-test
    'resolver-sim.protocols.sew.phase-m-test
    'resolver-sim.scenario.expectations-test
    'resolver-sim.scenario.equilibrium-test
    'resolver-sim.sim.multi-epoch-test
    'resolver-sim.sim.defection-test
    'resolver-sim.sim.strategy-adaptation-test
    'resolver-sim.sim.waterfall-test
    'resolver-sim.io.scenario-fixture-parity-test
    'resolver-sim.contract-model.replay-batch-test
    'resolver-sim.contract-model.replay-batch-sew-test
    'resolver-sim.contract-model.replay-batch-appeal-test
    'resolver-sim.contract-model.replay-batch-slash-domain-test
    'resolver-sim.protocols.sew.dispute-resolution-coverage-test
    'resolver-sim.financial.pro-rata-characterization-test
    'resolver-sim.scenario.suites-test
    'resolver-sim.validation.scenario-registry-test
    'resolver-sim.run.overview-test
    'resolver-sim.evidence.node-test
    'resolver-sim.evidence.attestation-dag-test)]
  (when (pos? (+ (:error results) (:fail results)))
    (System/exit 1)))"
  return $?
}

run_framework() {
  require_clojure || return $?
  echo "Running framework unit tests (no Sew protocol)..."
  clojure -M:test -e "
(require '[clojure.test :as t])
(require '[resolver-sim.core-tests])
(require '[resolver-sim.protocol-alignment-test])
(require '[resolver-sim.time.context-test])
(require '[resolver-sim.time.model-test])
(require '[resolver-sim.sim.defection-test])
(require '[resolver-sim.sim.strategy-adaptation-test])
(require '[resolver-sim.sim.waterfall-test])
(let [results (t/run-tests
                'resolver-sim.core-tests
                'resolver-sim.protocol-alignment-test
                'resolver-sim.time.context-test
                'resolver-sim.time.model-test
                'resolver-sim.sim.defection-test
                'resolver-sim.sim.strategy-adaptation-test
                'resolver-sim.sim.waterfall-test)]
  (when (pos? (+ (:error results) (:fail results)))
    (System/exit 1)))"
  return $?
}

run_sew() {
  require_clojure || return $?
  echo "Running Sew protocol unit tests..."
  clojure -M:test:with-sew -e "
(require '[clojure.test :as t])
(require '[resolver-sim.core-tests])
(require '[resolver-sim.protocols.sew.replay-test])
(require '[resolver-sim.protocols.sew.forking-strategist-expectations-test])
(require '[resolver-sim.scenario.expectations-test])
(require '[resolver-sim.scenario.equilibrium-test])
(require '[resolver-sim.protocols.sew.slashing-test])
(require '[resolver-sim.protocols.sew.phase-k-test])
(require '[resolver-sim.protocols.sew.phase-m-test])
(require '[resolver-sim.contract-model.replay-batch-sew-test])
(require '[resolver-sim.contract-model.replay-batch-appeal-test])
(require '[resolver-sim.contract-model.replay-batch-slash-domain-test])
(require '[resolver-sim.protocols.sew.lifecycle-test])
(require '[resolver-sim.protocols.sew.resolution-test])
(require '[resolver-sim.protocols.sew.state-machine-test])
(require '[resolver-sim.protocols.sew.governance-test])
(require '[resolver-sim.protocols.sew.integration-test])
(require '[resolver-sim.protocols.sew.accounting-test])
(require '[resolver-sim.protocols.sew.governance-gates-test])
(require '[resolver-sim.protocols.sew.yield-reorg-race-test])
(require '[resolver-sim.protocols.sew.yield-solvency-test])
(require '[resolver-sim.protocols.sew.yield.failure-test])
(require '[resolver-sim.protocols.sew.yield.policy-test])
(require '[resolver-sim.protocols.sew.yield.finalize-parity-test])
(require '[resolver-sim.protocols.sew.resolver-yield-accrual-test])
(require '[resolver-sim.protocols.sew.invariant-registry-test])
(require '[resolver-sim.protocols.sew.invariant-runner-test])
(require '[resolver-sim.protocols.sew.dispute-capacity-test])
(require '[resolver-sim.protocols.sew.funds-ledger-projection-test])
(require '[resolver-sim.protocols.sew.snapshot-test])
(require '[resolver-sim.protocols.sew.snapshot-boundary-test])
(require '[resolver-sim.protocols.sew.claimable-classification-test])
(require '[resolver-sim.protocols.sew.replay-bridge-test])
(require '[resolver-sim.protocols.sew.replay-dedupe-policy-test])
(require '[resolver-sim.protocols.sew.replay-event-id-scenario-test])
(require '[resolver-sim.protocols.sew.replay-idempotency-test])
(require '[resolver-sim.protocols.sew.require-event-id-test])
(require '[resolver-sim.protocols.sew.temporal-boundary-test])
(require '[resolver-sim.protocols.sew.temporal-generator-test])
(require '[resolver-sim.protocols.sew.trace-export-idempotency-test])
(require '[resolver-sim.protocols.sew.authority-test])
(require '[resolver-sim.protocols.sew.idempotence-checklist-test])
(require '[resolver-sim.protocols.sew.invariants.solvency-test])
(require '[resolver-sim.protocols.sew.invariants.temporal-test])
(require '[resolver-sim.scenario.subgame-counterfactual-test])
(require '[resolver-sim.scenario.yield-expectations-test])
(require '[resolver-sim.scenario.yield-scenario-lint-test])
(require '[resolver-sim.scenario.report-test])
(require '[resolver-sim.scenario.runner-test])
(require '[resolver-sim.scenario.theory-test])
(require '[resolver-sim.scenario.golden-test])
(require '[resolver-sim.scenario.phase-3-spe-test])
(require '[resolver-sim.scenario.spe-fork-event-id-test])
(require '[resolver-sim.properties.invariants-test])
(require '[resolver-sim.definitions.registry-test])
(require '[resolver-sim.evidence.registry-test])
(require '[resolver-sim.evidence.qol-test])
(require '[resolver-sim.protocols.sew.evidence.slashing-test])
(let [results (t/run-tests
                'resolver-sim.core-tests
                'resolver-sim.protocols.sew.replay-test
                'resolver-sim.protocols.sew.forking-strategist-expectations-test
                'resolver-sim.scenario.expectations-test
                'resolver-sim.scenario.equilibrium-test
                'resolver-sim.protocols.sew.slashing-test
                'resolver-sim.protocols.sew.evidence.slashing-test
                'resolver-sim.protocols.sew.phase-k-test
                'resolver-sim.protocols.sew.phase-m-test
                'resolver-sim.contract-model.replay-batch-sew-test
                'resolver-sim.contract-model.replay-batch-appeal-test
                'resolver-sim.contract-model.replay-batch-slash-domain-test
                'resolver-sim.protocols.sew.lifecycle-test
                'resolver-sim.protocols.sew.resolution-test
                'resolver-sim.protocols.sew.state-machine-test
                'resolver-sim.protocols.sew.governance-test
                'resolver-sim.protocols.sew.integration-test
                'resolver-sim.protocols.sew.accounting-test
                'resolver-sim.protocols.sew.governance-gates-test
                'resolver-sim.protocols.sew.yield-reorg-race-test
                'resolver-sim.protocols.sew.yield-solvency-test
                'resolver-sim.protocols.sew.yield.failure-test
                'resolver-sim.protocols.sew.yield.policy-test
                'resolver-sim.protocols.sew.yield.finalize-parity-test
                'resolver-sim.protocols.sew.resolver-yield-accrual-test
                'resolver-sim.protocols.sew.invariant-registry-test
                'resolver-sim.protocols.sew.invariant-runner-test
                'resolver-sim.protocols.sew.dispute-capacity-test
                'resolver-sim.protocols.sew.funds-ledger-projection-test
                'resolver-sim.protocols.sew.snapshot-test
                'resolver-sim.protocols.sew.snapshot-boundary-test
                'resolver-sim.protocols.sew.claimable-classification-test
                'resolver-sim.protocols.sew.replay-bridge-test
                'resolver-sim.protocols.sew.replay-dedupe-policy-test
                'resolver-sim.protocols.sew.replay-event-id-scenario-test
                'resolver-sim.protocols.sew.replay-idempotency-test
                'resolver-sim.protocols.sew.require-event-id-test
                'resolver-sim.protocols.sew.temporal-boundary-test
                'resolver-sim.protocols.sew.temporal-generator-test
                'resolver-sim.protocols.sew.trace-export-idempotency-test
                'resolver-sim.protocols.sew.authority-test
                'resolver-sim.protocols.sew.idempotence-checklist-test
                'resolver-sim.protocols.sew.invariants.solvency-test
                'resolver-sim.protocols.sew.invariants.temporal-test
                'resolver-sim.scenario.subgame-counterfactual-test
                'resolver-sim.scenario.yield-expectations-test
                'resolver-sim.scenario.yield-scenario-lint-test
                'resolver-sim.scenario.report-test
                'resolver-sim.scenario.runner-test
                'resolver-sim.scenario.theory-test
                'resolver-sim.scenario.golden-test
                'resolver-sim.scenario.phase-3-spe-test
                'resolver-sim.scenario.spe-fork-event-id-test
                'resolver-sim.properties.invariants-test
                'resolver-sim.definitions.registry-test
                'resolver-sim.evidence.qol-test
                'resolver-sim.evidence.registry-test)]
  (when (pos? (+ (:error results) (:fail results)))
    (System/exit 1)))"
  return $?
}

run_yield() {
  require_clojure || return $?
  echo "Running yield protocol unit tests..."
  clojure -M:test:with-sew -e "
(require '[clojure.test :as t])
(require '[resolver-sim.protocols.sew.yield-reorg-race-test])
(require '[resolver-sim.protocols.sew.yield-solvency-test])
(require '[resolver-sim.protocols.sew.yield.failure-test])
(require '[resolver-sim.protocols.sew.yield.policy-test])
(require '[resolver-sim.protocols.sew.yield.finalize-parity-test])
(require '[resolver-sim.protocols.sew.resolver-yield-accrual-test])
(require '[resolver-sim.scenario.yield-expectations-test])
(require '[resolver-sim.scenario.yield-scenario-lint-test])
(let [results (t/run-tests
               'resolver-sim.protocols.sew.yield-reorg-race-test
               'resolver-sim.protocols.sew.yield-solvency-test
               'resolver-sim.protocols.sew.yield.failure-test
               'resolver-sim.protocols.sew.yield.policy-test
               'resolver-sim.protocols.sew.yield.finalize-parity-test
               'resolver-sim.protocols.sew.resolver-yield-accrual-test
               'resolver-sim.scenario.yield-expectations-test
               'resolver-sim.scenario.yield-scenario-lint-test)]
  (when (pos? (+ (:error results) (:fail results)))
    (System/exit 1)))"
  return $?
}

run_invariants() {
  require_clojure || return $?
  echo "Running deterministic invariant scenarios (S01–S100)..."
  clojure -M:run:with-sew:with-sew -- --invariants
  return $?
}

run_dispute_resolution() {
  require_clojure || return $?
  echo "Running dispute resolution coverage scenarios (S-DR-* via test namespace)..."
  clojure -M:test:with-sew -e "
(require '[clojure.test :as t])
(require '[resolver-sim.protocols.sew.dispute-resolution-coverage-test])
(let [results (t/run-tests 'resolver-sim.protocols.sew.dispute-resolution-coverage-test)]
  (when (pos? (+ (:error results) (:fail results)))
    (System/exit 1)))"
  local dr_exit=$?
  # CI Gate: validate artifact registry
  if [[ -f "scripts/validate/ci_gate_validation.py" ]]; then
    echo "Running CI gate validation..."
    python3 scripts/validate/ci_gate_validation.py || return $?
  fi

  # CI Gate: coverage gates
  python3 scripts/validate/coverage_gates.py --artifact-dir "$ARTIFACT_DIR" --max-unhit-transitions "$MAX_UNHIT_TRANSITIONS" || return $?

  return $dr_exit
}

run_named_path_suite() {
  require_clojure || return $?
  local suite="$1"
  echo "Running registry-backed scenario path suite: $suite"
  clojure -M:run:with-sew:with-sew -- --invariants --suite "$suite"
  return $?
}

run_yield_provider_scenarios() {
  echo "WARN: test.sh yield-provider-scenarios is deprecated. Use bb test:yield-provider-scenarios instead." >&2
  run_named_path_suite yield-provider-scenarios
}

run_sew_yield_scenarios() {
  echo "WARN: test.sh sew-yield-scenarios is deprecated. Use bb test:sew-yield-scenarios instead." >&2
  run_named_path_suite sew-yield-scenarios
}

run_yield_scenarios() {
  echo "WARN: test.sh yield-scenarios is deprecated. Use bb test:yield-scenarios instead." >&2
  echo "yield-scenarios is a compatibility alias; running yield-provider-scenarios then sew-yield-scenarios."
  run_yield_provider_scenarios || return $?
  run_sew_yield_scenarios
}

run_generators() {
  require_clojure || return $?
  echo "Running generator regression tests (pinned seeds)..."
  clojure -M:test -e "
(require '[clojure.test :as t])
(require '[resolver-sim.generators.equilibrium-test])
(require '[resolver-sim.generators.fixtures-test])
(require '[resolver-sim.properties.invariants-test])
(let [results (t/run-tests
    'resolver-sim.generators.equilibrium-test
    'resolver-sim.generators.fixtures-test
    'resolver-sim.properties.invariants-test)]
  (when (pos? (+ (:error results) (:fail results)))
    (System/exit 1)))"
  return $?
}

run_contracts() {
  echo "Running cross-layer contract checks (proto/service/wire compatibility)..."

  # Proto service + RPC contract
  grep -q 'package sew.simulation;' proto/simulation.proto
  grep -q 'service SimulationEngine' proto/simulation.proto
  grep -q 'rpc StartSession' proto/simulation.proto
  grep -q 'rpc Step' proto/simulation.proto
  grep -q 'rpc DestroySession' proto/simulation.proto

  # Python client must target same service/methods
  grep -q '_SERVICE = "sew.simulation.SimulationEngine"' python/sim_api/grpc_client.py
  grep -q 'StartSession' python/sim_api/grpc_client.py
  grep -q 'DestroySession' python/sim_api/grpc_client.py

  # Clojure server must expose same RPC names and snake_case↔kebab-case bridge
  grep -q 'SimulationEngine' src/resolver_sim/server/grpc.clj
  grep -q 'make-method "StartSession"' src/resolver_sim/server/grpc.clj
  grep -q 'make-method "Step"' src/resolver_sim/server/grpc.clj
  grep -q 'make-method "DestroySession"' src/resolver_sim/server/grpc.clj
  grep -q 'snake_case' src/resolver_sim/server/grpc.clj

  # Scenario naming convention sanity checks (supports legacy + canonical ids)
  python scripts/validate/validate_scenario_naming.py

  # P1: Fixture/claim alignment checks for collusion assertions
  python scripts/validate/validate_collusion_alignment.py

  # Artifact registry integrity + compatibility checks
  python scripts/validate/validate_artifact_registry.py

  # Claim registry integrity checks (claim ids ↔ scenarios ↔ invariants)
  if [ "$STRICT_CLAIM_REGISTRY" = "1" ]; then
    python scripts/validate/validate_claim_registry.py --strict-theory-claims
  else
    python scripts/validate/validate_claim_registry.py
  fi

  return $?
}

run_triage() {
  require_clojure || return $?
  echo "Running failure triage (purpose/threat-tag grouping)..."
  clojure -M -m resolver-sim.scenario.triage ${1:-data/fixtures/traces}
  return $?
}

run_suites() {
   require_clojure || return $?
   echo "Running all canonical fixture suites (save goldens + emit test artifacts)..."
   local suite_filter="[:suites/all-invariants :suites/baseline-safety :suites/equilibrium-validation :suites/spe-validation :suites/spe-regression :suites/equivalence-auth-paths :suites/equivalence-race-pairs :suites/equivalence-escalation-boundaries :suites/equivalence-accounting-min :suites/equivalence-money-path-integrity :suites/dr3-critical :suites/governance-decay :suites/same-block-ordering :suites/timelock-regression :suites/equivalence-economic-stress :suites/forking-strategist]"

   clojure -M:test:with-sew -e "
(require '[resolver-sim.sim.fixtures :as f])
(let [suites $suite_filter
      results (map (fn [id] [id (f/run-suite id :save nil {})]) suites)
      any-fail (some (fn [[_ r]] (not (:ok? r))) results)]
  (doseq [[suite-id result] results]
    (f/emit-suite-result suite-id result)
    (println (str suite-id " → " (if (:ok? result) "PASS" "FAIL")))
    (when-not (:ok? result)
      (doseq [r (:results result)]
        (when (not= :pass (:outcome r))
          (println (str "  FAIL: " (:trace-id r) " [" (:outcome r) "]")))))))
  (when any-fail (System/exit 1)))"
   # Extract suite failures into risk digest format for test-summary.json visibility
   python3 scripts/evidence/suite_failures_to_risk.py --artifact-dir "$ARTIFACT_DIR" --run-id "$RUN_ID"
   # Touch notebooks/report.clj so Clerk's file watcher triggers re-evaluation
   touch -m "notebooks/report.clj" 2>/dev/null || true
   return $?
}

run_routed_suites() {
  require_clojure || return $?
  local routed=$(python3 scripts/validate/route_suites.py)
  if [ "$routed" = "none" ]; then
    echo "No suites impacted by changes. Skipping."
    return 0
  fi

  local suite_filter
  if [ "$routed" = "all" ]; then
    echo "Changes impact all suites. Running full suite."
    suite_filter="[:suites/all-invariants :suites/equilibrium-validation :suites/spe-validation :suites/spe-regression]"
  else
    echo "Changes impact suites: $routed"
    suite_filter="[$(echo $routed | sed 's/,/ /g')]"
  fi

  clojure -M:test:with-sew -e "
(require '[resolver-sim.sim.fixtures :as f])
(let [suites $suite_filter
      verify-opts {:golden-verify-mode :replay-and-theory}
      results (map (fn [id] [id (f/run-suite id :verify nil verify-opts)]) suites)
      any-fail (some (fn [[_ r]] (not (:ok? r))) results)]
  (doseq [[suite-id result] results]
    (f/emit-suite-result suite-id result)
    (println (str suite-id " → " (if (:ok? result) "PASS" "FAIL")))
    (when-not (:ok? result)
      (doseq [r (:results result)]
        (when (not= :pass (:outcome r))
          (println (str "  FAIL: " (:trace-id r) " [" (:outcome r) "]")))))))
  (when any-fail (System/exit 1)))"
  return $?
}

run_reference_validation() {
  require_clojure || return $?
  echo "Running reference validation suite v1..."
  make clean-reference-validation-v1 >/dev/null
  make reference-validation-v1
  make verify-reference-validation-v1
  return $?
}

run_dr3_coverage() {
  require_clojure || return $?
  echo "Running DR3 critical suite + release-mapping drift checks..."

  # 1) Run the dedicated DR3-critical suite.
  clojure -M:test:with-sew -e "
(require '[resolver-sim.sim.fixtures :as f])
(let [r (f/run-suite :suites/dr3-critical)]
  (f/emit-suite-result :suites/dr3-critical r)
  (println (str :suites/dr3-critical " → " (if (:ok? r) "PASS" "FAIL")))
  (when-not (:ok? r)
    (doseq [x (:results r)]
      (when (not= :pass (:outcome x))
        (println (str "  FAIL: " (:trace-id x) " [" (:outcome x) "]"))))))
  (when-not (:ok? r) (System/exit 1)))" || return $?

  # 2) Verify DR3 release mapping file references valid traces and suite IDs.
  clojure -M:test:with-sew -e "
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(let [m (edn/read-string (slurp "data/fixtures/protocol/dr3-release-modules.edn"))
      traces (:dr3-critical-traces m)
      suites (:dr3-critical-suites m)
      missing-traces (->> traces
                          (map name)
                          (map #(str "data/fixtures/traces/" % ".trace.json"))
                          (remove #(-> % io/file .exists))
                          vec)
      missing-suites (->> suites
                          (map name)
                          (map #(str "data/fixtures/suites/" % ".edn"))
                          (remove #(-> % io/file .exists))
                          vec)]
  (when (seq missing-traces)
    (println "Missing DR3 mapped traces:")
    (doseq [p missing-traces] (println " -" p))
    (System/exit 1))
  (when (seq missing-suites)
    (println "Missing DR3 mapped suites:")
    (doseq [p missing-suites] (println " -" p))
    (System/exit 1))
  (println "DR3 mapping drift checks passed"))" || return $?

  return $?
}

run_equivalence_new() {
  require_clojure || return $?
  echo "Running new equivalence comparison suites (auth/race/escalation/accounting + money-path)..."
  clojure -M:test:with-sew -e "
(require '[resolver-sim.sim.fixtures :as f])
(let [suites [:suites/equivalence-auth-paths
          :suites/equivalence-race-pairs
          :suites/equivalence-escalation-boundaries
          :suites/equivalence-accounting-min
          :suites/equivalence-money-path-integrity]
      results (map (fn [id] [id (f/run-suite id)]) suites)
      any-fail (some (fn [[_ r]] (not (:ok? r))) results)]
  (doseq [[suite-id result] results]
    (f/emit-suite-result suite-id result)
    (println (str suite-id " → " (if (:ok? result) "PASS" "FAIL")))
    (when-not (:ok? result)
      (doseq [r (:results result)]
        (when (not= :pass (:outcome r))
          (println (str "  FAIL: " (:trace-id r) " [" (:outcome r) "]")))))))
  (when any-fail (System/exit 1)))"

  _eq_out=$(python3 -c "from evidence_config import EvidenceConfig; c=EvidenceConfig(); print(c.artifact_path('equivalence-summary'))")
  python3 python/equivalence_pair_diff.py \
    --traces-dir data/fixtures/traces \
    --out "$_eq_out" \
    --replay-dir "$ARTIFACT_DIR/equivalence-pairs" || true

  return $?
}

run_comparison_lint() {
  echo "Running comparison metadata lint..."
  python scripts/validate/validate_comparison_metadata.py
  return $?
}

run_coverage_gates() {
  require_clojure || return $?
  echo "Running transition/guard coverage report + gates..."
  mkdir -p "$ARTIFACT_DIR"
  _cov_out=$(python3 -c "from evidence_config import EvidenceConfig; print(EvidenceConfig().artifact_path('coverage'))" 2>/dev/null) || _cov_out="$ARTIFACT_DIR/coverage.json"
  clojure -M -m resolver-sim.scenario.coverage -- data/fixtures/traces "$_cov_out" || return $?
  python3 scripts/validate/coverage_gates.py --artifact-dir "$ARTIFACT_DIR" --max-unhit-transitions "$MAX_UNHIT_TRANSITIONS"
  return $?
}

run_adversarial_sweep() {
  echo "Running adversarial profitability sweep..."
  python3 python/adversarial_profitability_sweep.py --top-n 10
  return $?
}

run_adversarial_gates() {
  echo "Running adversarial profitability gates..."
  latest_dir=$(ls -1dt results/profitability-surfaces/* 2>/dev/null | head -n 1)
  if [ -z "$latest_dir" ]; then
    echo "No profitability surface output found. Run adversarial-sweep first."
    return 1
  fi
  python3 scripts/validate/adversarial_gates.py "$latest_dir"
  return $?
}

emit_claimable_classification() {
  require_clojure || return 0
  echo "Emitting claimable-classification v2..."
  if [ "${CLAIMABLE_CLASSIFICATION_TAXONOMY_ONLY:-0}" = "1" ]; then
    clojure -M:with-sew -m resolver-sim.io.claimable-classification-emitter \
      "$CLAIMABLE_CLASSIFICATION_FILE" taxonomy-only
  else
    clojure -M:with-sew -m resolver-sim.io.claimable-classification-emitter \
      "$CLAIMABLE_CLASSIFICATION_FILE" aggregated "$RUN_ID"
  fi
}

run_outcome_classification_report() {
  echo ""
  echo "Outcome classification report"
  echo "============================="
  python3 scripts/evidence/outcome_classification_report.py "$ARTIFACT_FILE"
  return $?
}

case "$MODE" in
  unit)
    run_unit || FAILURES=$((FAILURES + 1))
    ;;
  framework)
    run_framework || FAILURES=$((FAILURES + 1))
    ;;
  sew)
    run_sew || FAILURES=$((FAILURES + 1))
    ;;
  invariants)
    run_invariants || FAILURES=$((FAILURES + 1))
    ;;
  dispute-resolution)
    run_dispute_resolution || FAILURES=$((FAILURES + 1))
    ;;
  yield-provider-scenarios)
    run_yield_provider_scenarios || FAILURES=$((FAILURES + 1))
    ;;
  sew-yield-scenarios)
    run_sew_yield_scenarios || FAILURES=$((FAILURES + 1))
    ;;
  yield-scenarios)
    run_yield_scenarios || FAILURES=$((FAILURES + 1))
    ;;
  yield)
    run_yield || FAILURES=$((FAILURES + 1))
    ;;
  generators)
    run_generators || FAILURES=$((FAILURES + 1))
    ;;
  contracts)
    run_target contracts run_contracts || FAILURES=$((FAILURES + 1))
    ;;
  triage)
    run_target triage run_triage || FAILURES=$((FAILURES + 1))
    ;;
  suites)
    run_target suites run_suites || FAILURES=$((FAILURES + 1))
    ;;
  routed-suites)
    run_target routed-suites run_routed_suites || FAILURES=$((FAILURES + 1))
    ;;
  reference-validation)
    run_target reference-validation run_reference_validation || FAILURES=$((FAILURES + 1))
    ;;
  dr3-coverage)
    run_target dr3-coverage run_dr3_coverage || FAILURES=$((FAILURES + 1))
    ;;
  equivalence-new)
    run_target equivalence-new run_equivalence_new || FAILURES=$((FAILURES + 1))
    ;;
  comparison-lint)
    run_target comparison-lint run_comparison_lint || FAILURES=$((FAILURES + 1))
    ;;
  coverage)
    run_target coverage run_coverage_gates || FAILURES=$((FAILURES + 1))
    ;;
  adversarial-sweep)
    run_target adversarial-sweep run_adversarial_sweep || FAILURES=$((FAILURES + 1))
    ;;
  adversarial-gates)
    run_target adversarial-gates run_adversarial_gates || FAILURES=$((FAILURES + 1))
    ;;
  monte-carlo)
    run_target monte-carlo run_monte_carlo || FAILURES=$((FAILURES + 1))
    ;;
  long-horizon)
    run_target long-horizon run_long_horizon || FAILURES=$((FAILURES + 1))
    ;;
  all)
    : > "$ARTIFACT_DIR/.targets-${RUN_ID}.csv"
    run_target unit run_unit || FAILURES=$((FAILURES + 1))
    echo ""
    run_target generators run_generators || FAILURES=$((FAILURES + 1))
    echo ""
    run_target contracts run_contracts || FAILURES=$((FAILURES + 1))
    echo ""
    run_target invariants run_invariants || FAILURES=$((FAILURES + 1))
    echo ""
    run_target suites run_suites || FAILURES=$((FAILURES + 1))
    echo ""
    run_target reference-validation run_reference_validation || FAILURES=$((FAILURES + 1))
    echo ""
    run_target coverage run_coverage_gates || FAILURES=$((FAILURES + 1))
    echo ""
    run_target triage run_triage || FAILURES=$((FAILURES + 1))
    echo ""
    run_target monte-carlo run_monte_carlo || FAILURES=$((FAILURES + 1))
    run_outcome_classification_report || true

    # CI Gate: coverage gates validation for all mode
    python3 scripts/validate/coverage_gates.py --artifact-dir "$ARTIFACT_DIR" --max-unhit-transitions "$MAX_UNHIT_TRANSITIONS" || FAILURES=$((FAILURES + 1))
    ;;
  *)
    echo "Unknown mode: $MODE"
    echo "Usage: $0 [unit|framework|sew|generators|contracts|invariants|dispute-resolution|yield-provider-scenarios|sew-yield-scenarios|yield-scenarios|suites|reference-validation|dr3-coverage|equivalence-new|comparison-lint|coverage|adversarial-sweep|adversarial-gates|triage|monte-carlo|long-horizon|all]"
    exit 1
    ;;
esac

if [ -f scripts/evidence/generate_test_summary.py ]; then
  python3 scripts/evidence/generate_test_summary.py \
    "$ARTIFACT_DIR" \
    "$RUN_ID" \
    "$FAILURES" \
    "$MODE" \
    "$ARTIFACT_FILE" \
    "$RUN_MANIFEST_FILE" \
    "$ARTIFACT_REGISTRY_FILE" \
    "$CLAIMABLE_CLASSIFICATION_FILE"
fi

exit $FAILURES
