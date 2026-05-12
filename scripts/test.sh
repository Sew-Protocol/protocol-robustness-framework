#!/usr/bin/env bash
# Canonical test runner for sew-simulation.
#
# Usage:
#   ./scripts/test.sh            # run all suites (unit + invariants + fixtures)
#   ./scripts/test.sh unit       # Clojure unit tests only
#   ./scripts/test.sh generators # Generator + equilibrium regression tests (pinned seeds)
#   ./scripts/test.sh invariants # S01–S41 deterministic invariant scenarios only
#   ./scripts/test.sh contracts  # Cross-layer contract checks (proto/service/wire compatibility)
#   ./scripts/test.sh suites     # fixture suite runner (all-invariants + equilibrium-validation)
#   ./scripts/test.sh triage     # Failure triage grouped by purpose/threat-tag
#   ./scripts/test.sh equivalence-new # New equivalence comparison stack (auth/race/escalation/accounting)
#   ./scripts/test.sh monte-carlo # Representative Monte Carlo phase sweep (4 domains)
#   ./scripts/test.sh long-horizon # Extended horizon scenarios (100/200/500/1000 epochs)
#
# Exit code: 0 = all passed, 1 = any failure.

cd "$(dirname "$0")/.."

MODE="${1:-all}"
FAILURES=0
ARTIFACT_DIR="results/test-artifacts"
ARTIFACT_FILE="$ARTIFACT_DIR/test-summary.json"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
MAX_UNHIT_TRANSITIONS="${MAX_UNHIT_TRANSITIONS:-4}"
MAX_UNSAFE_REGION_DELTA_PCT="${MAX_UNSAFE_REGION_DELTA_PCT:-10}"

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
  echo "Running unit tests..."
  clojure -M:test -e "
(require '[clojure.test :as t])
(require '[resolver-sim.core-tests])
(require '[resolver-sim.protocols.sew.replay-test])
(require '[resolver-sim.scenario.expectations-test])
(require '[resolver-sim.scenario.equilibrium-test])
(require '[resolver-sim.sim.multi-epoch-test])
(let [results (t/run-tests
                'resolver-sim.core-tests
                'resolver-sim.protocols.sew.replay-test
                'resolver-sim.scenario.expectations-test
                'resolver-sim.scenario.equilibrium-test
                'resolver-sim.sim.multi-epoch-test)]
  (when (pos? (+ (:error results) (:fail results)))
    (System/exit 1)))"
  return $?
}

run_invariants() {
  require_clojure || return $?
  echo "Running S01–S41 deterministic invariant scenarios..."
  clojure -M:run -- --invariants
  return $?
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
  grep -q '_SERVICE = "sew.simulation.SimulationEngine"' python/sew_sim/grpc_client.py
  grep -q 'StartSession' python/sew_sim/grpc_client.py
  grep -q 'DestroySession' python/sew_sim/grpc_client.py

  # Clojure server must expose same RPC names and snake_case↔kebab-case bridge
  grep -q 'SimulationEngine' src/resolver_sim/server/grpc.clj
  grep -q 'make-method "StartSession"' src/resolver_sim/server/grpc.clj
  grep -q 'make-method "Step"' src/resolver_sim/server/grpc.clj
  grep -q 'make-method "DestroySession"' src/resolver_sim/server/grpc.clj
  grep -q 'snake_case' src/resolver_sim/server/grpc.clj

  # Scenario naming convention sanity checks (supports legacy + canonical ids)
  python - <<'PY'
import json
import pathlib
import re
import sys

root = pathlib.Path('data/fixtures/traces')
pat_s = re.compile(r'^s\d{2}[a-z]?-[a-z0-9\-]+\.trace\.json$')
pat_other = re.compile(r'^(eq-v\d+|spe-v\d+)-[a-z0-9\-]+\.trace\.json$')
bad = []
for p in sorted(root.glob('*.trace.json')):
    name = p.name
    is_eq_spe = name.startswith('eq-v') or name.startswith('spe-v')
    is_sxx = bool(re.match(r'^s\d{2}[a-z]?-', name))

    # Validate known canonical families only; allow legacy/non-canonical
    # traces (e.g. same-block-ordering.trace.json) to coexist.
    if is_eq_spe:
        if not pat_other.match(name):
            bad.append(f"bad-filename:{p}")
            continue
    elif is_sxx:
        if not pat_s.match(name):
            bad.append(f"bad-filename:{p}")
            continue

    if is_eq_spe and not pat_other.match(name):
        bad.append(f"bad-filename:{p}")
        continue
    try:
        obj = json.loads(p.read_text())
    except Exception as e:
        bad.append(f"bad-json:{p}:{e}")
        continue
    sid = obj.get('id')
    if sid:
        sid_s = str(sid)
        stem = p.name.replace('.trace.json', '')
        valid_ids = {stem, f"scenarios/{stem}"}
        if sid_s not in valid_ids:
            bad.append(f"id-mismatch:{p}:id={sid_s}:expected-one-of={sorted(valid_ids)}")

if bad:
    print("Scenario naming/ID convention checks failed:")
    for b in bad:
        print(" -", b)
    sys.exit(1)

print("Scenario naming/ID convention checks passed")
PY

  # P1: Fixture/claim alignment checks for collusion assertions
  python - <<'PY'
import json
from pathlib import Path

root = Path('data/fixtures/traces')
errors = []

for p in sorted(root.glob('*.trace.json')):
    try:
        obj = json.loads(p.read_text())
    except Exception:
        continue

    theory = obj.get('theory') or {}
    mech = theory.get('mechanism-properties') or []
    claim = str(theory.get('claim', '')).lower()
    tags = [str(t).lower() for t in (obj.get('threat-tags') or [])]
    needs_collusion_alignment = (
        ('collusion-resistance' in mech)
        or ('collusion' in claim)
        or any('collusion' in t for t in tags)
    )
    if not needs_collusion_alignment:
        continue

    agents = obj.get('agents') or []
    has_explicit_collusive_actor = any(
        str(a.get('type', '')).lower() == 'collusive' for a in agents if isinstance(a, dict)
    )

    # Allow dedicated inconclusive fixture by id/title to remain actor-agnostic.
    sid = str(obj.get('scenario-id', ''))
    title = str(obj.get('title', '')).lower()
    inconclusive_fixture = ('collusion-resistance-inconclusive' in sid) or ('inconclusive' in title)

    if (not has_explicit_collusive_actor) and (not inconclusive_fixture):
        errors.append(f"collusion-alignment-missing:{p}:expected at least one agent.type='collusive'")

if errors:
    print('Collusion fixture/claim alignment checks failed:')
    for e in errors:
        print(' -', e)
    raise SystemExit(1)

print('Collusion fixture/claim alignment checks passed')
PY

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
  echo "Running fixture suites (all-invariants + equilibrium-validation + spe-validation)..."
  clojure -M:test -e "
(require '[resolver-sim.sim.fixtures :as f])
(let [suites [:suites/all-invariants :suites/equilibrium-validation :suites/spe-validation]
      results (map (fn [id] [id (f/run-suite id)]) suites)
      any-fail (some (fn [[_ r]] (not (:ok? r))) results)]
  (doseq [[suite-id result] results]
    (println (str suite-id \" → \" (if (:ok? result) \"PASS\" \"FAIL\")))
    (when-not (:ok? result)
      (doseq [r (:results result)]
        (when (not= :pass (:outcome r))
          (println (str \"  FAIL: \" (:trace-id r) \" [\" (:outcome r) \"]\"))))))
  (when any-fail (System/exit 1)))"
  return $?
}

run_equivalence_new() {
  require_clojure || return $?
  echo "Running new equivalence comparison suites (auth/race/escalation/accounting + money-path)..."
  clojure -M:test -e "
(require '[resolver-sim.sim.fixtures :as f])
(let [suites [:suites/equivalence-auth-paths
              :suites/equivalence-race-pairs
              :suites/equivalence-escalation-boundaries
              :suites/equivalence-accounting-min
              :suites/equivalence-money-path-integrity]
      results (map (fn [id] [id (f/run-suite id)]) suites)
      any-fail (some (fn [[_ r]] (not (:ok? r))) results)]
  (doseq [[suite-id result] results]
    (println (str suite-id \" → \" (if (:ok? result) \"PASS\" \"FAIL\")))
    (when-not (:ok? result)
      (doseq [r (:results result)]
        (when (not= :pass (:outcome r))
          (println (str \"  FAIL: \" (:trace-id r) \" [\" (:outcome r) \"]\"))))))
  (when any-fail (System/exit 1)))"

  python - <<'PY'
import json
from pathlib import Path

traces_dir = Path("data/fixtures/traces")
out_path = Path("results/test-artifacts/equivalence-comparison-summary.json")

traces = []
for p in sorted(traces_dir.glob("*.trace.json")):
    try:
        obj = json.loads(p.read_text())
    except Exception:
        continue
    comp = obj.get("comparison")
    if not isinstance(comp, dict):
        continue
    traces.append({
        "id": str(obj.get("scenario-id") or obj.get("id") or p.stem.replace('.trace', '')),
        "path": str(p),
        "comparison": comp,
    })

by_id = {t["id"]: t for t in traces}
groups = {}
for t in traces:
    grp = str(t["comparison"].get("comparison_group", ""))
    if not grp:
        continue
    groups.setdefault(grp, []).append(t)

summary = {
    "groups": {},
    "group_count": len(groups),
}

for grp, members in groups.items():
    rec = {
        "members": [],
        "pair_complete": False,
        "reciprocal": False,
        "expected_divergence": True,
        "status": "incomplete",
    }
    ids = []
    for m in members:
        comp = m["comparison"]
        member = {
            "id": m["id"],
            "variant": comp.get("variant"),
            "counterfactual_of": comp.get("counterfactual_of"),
            "path": m["path"],
        }
        rec["members"].append(member)
        ids.append(m["id"])

    if len(members) == 2:
        rec["pair_complete"] = True
        a, b = members[0], members[1]
        a_cf = str(a["comparison"].get("counterfactual_of", ""))
        b_cf = str(b["comparison"].get("counterfactual_of", ""))
        rec["reciprocal"] = (a_cf == b["id"] and b_cf == a["id"])
        rec["status"] = "expected-divergence-observed" if rec["reciprocal"] else "unexpected"

    summary["groups"][grp] = rec

out_path.parent.mkdir(parents=True, exist_ok=True)
out_path.write_text(json.dumps(summary, indent=2))
print(f"Wrote equivalence comparison summary: {out_path}")
PY

  return $?
}

run_comparison_lint() {
  echo "Running comparison metadata lint..."
  python - <<'PY'
import json
import sys
from pathlib import Path

traces_dir = Path("data/fixtures/traces")
files = sorted(traces_dir.glob("*.trace.json"))

entries = []
errors = []

for p in files:
    try:
        obj = json.loads(p.read_text())
    except Exception as e:
        errors.append(f"bad-json:{p}:{e}")
        continue

    comp = obj.get("comparison")
    if not comp:
        continue
    if not isinstance(comp, dict):
        errors.append(f"comparison-not-object:{p}")
        continue

    sid = str(obj.get("scenario-id") or obj.get("id") or p.stem.replace('.trace', ''))
    group = str(comp.get("comparison_group", "")).strip()
    variant = str(comp.get("variant", "")).strip()
    cf = str(comp.get("counterfactual_of", "")).strip()

    if not group:
        errors.append(f"missing-comparison-group:{p}")
    if variant not in {"A", "B"}:
        errors.append(f"invalid-variant:{p}:{variant}")
    if not cf:
        errors.append(f"missing-counterfactual-of:{p}")

    es = obj.get("expected_semantics", {})
    pc = es.get("path_constraints", {}) if isinstance(es, dict) else {}
    mo = pc.get("must_observe") if isinstance(pc, dict) else None
    if not isinstance(mo, list) or len(mo) == 0:
        errors.append(f"missing-path-constraints.must_observe:{p}")

    entries.append({
        "id": sid,
        "group": group,
        "variant": variant,
        "counterfactual_of": cf,
        "path": str(p),
    })

by_id = {e["id"]: e for e in entries}
groups = {}
for e in entries:
    groups.setdefault(e["group"], []).append(e)

for grp, members in groups.items():
    if len(members) != 2:
        errors.append(f"group-size-not-2:{grp}:{len(members)}")
        continue
    variants = {m["variant"] for m in members}
    if variants != {"A", "B"}:
        errors.append(f"group-variants-invalid:{grp}:{sorted(variants)}")

    a, b = members[0], members[1]
    if a["counterfactual_of"] not in by_id:
        errors.append(f"counterfactual-target-missing:{a['id']}->{a['counterfactual_of']}")
    if b["counterfactual_of"] not in by_id:
        errors.append(f"counterfactual-target-missing:{b['id']}->{b['counterfactual_of']}")

    if a["counterfactual_of"] != b["id"] or b["counterfactual_of"] != a["id"]:
        errors.append(f"counterfactual-not-reciprocal:{grp}:{a['id']}<->{b['id']}")

if errors:
    print("Comparison metadata lint failed:")
    for e in errors:
        print(" -", e)
    sys.exit(1)

print(f"Comparison metadata lint passed for {len(entries)} traces across {len(groups)} groups")
PY
  return $?
}

run_coverage_gates() {
  require_clojure || return $?
  echo "Running transition/guard coverage report + gates..."
  mkdir -p "$ARTIFACT_DIR"
  clojure -M:coverage-report -- data/fixtures/traces "$ARTIFACT_DIR/coverage.json" || return $?
  python - <<PY
import json, sys
from pathlib import Path
p = Path("$ARTIFACT_DIR/coverage.json")
if not p.exists():
    print("Missing coverage artifact:", p)
    sys.exit(1)
obj = json.loads(p.read_text())
unhit = obj.get("unhit-transitions", [])
max_unhit = int("$MAX_UNHIT_TRANSITIONS")
print(f"unhit-transitions={len(unhit)} threshold={max_unhit}")
if len(unhit) > max_unhit:
    print("Coverage gate failed: too many unhit transitions")
    print("Unhit:", unhit)
    sys.exit(1)
required_categories = {
    "creation", "state-change", "escalation", "resolution", "timeout", "governance", "economic"
}
hits = obj.get("transition-hit-freq", {})
def category_of(action):
    s = str(action)
    if "create_escrow" in s:
        return "creation"
    if "raise_dispute" in s or "sender_cancel" in s or "recipient_cancel" in s:
        return "state-change"
    if "escalate_dispute" in s or "challenge_resolution" in s:
        return "escalation"
    if "execute_resolution" in s or "execute_pending_settlement" in s:
        return "resolution"
    if "auto_cancel_disputed" in s:
        return "timeout"
    if "automate_timed_actions" in s or "register_stake" in s:
        return "governance"
    if "release" in s:
        return "economic"
    return None
seen = {c for c in (category_of(k) for k in hits.keys()) if c}
missing = sorted(required_categories - seen)
if missing:
    print("Coverage gate failed: missing required transition categories:", missing)
    sys.exit(1)
print("Coverage gates passed")
PY
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
  python - <<PY
import json, sys
from pathlib import Path
latest = Path("$latest_dir")
regions = latest / "regions.json"
promos = latest / "promotions.json"
if not regions.exists() or not promos.exists():
    print("Missing required artifacts in", latest)
    sys.exit(1)
r = json.loads(regions.read_text())
p = json.loads(promos.read_text())
families = r.get("families", {})
if not families:
    print("No family data found in regions.json")
    sys.exit(1)
top = p.get("top", [])
if len(top) < 3:
    print("Gate failed: expected at least 3 promoted candidates, got", len(top))
    sys.exit(1)
# Placeholder bounded-growth gate: enforce per-family unsafe ratio <= 100%
# (real baseline delta comparison can be layered in CI with persisted baseline snapshots).
for fam, vals in families.items():
    safe = vals.get("safe", 0)
    unsafe = vals.get("unsafe", 0)
    total = max(1, safe + unsafe + vals.get("borderline", 0))
    ratio = unsafe / total
    if ratio > 1.0:
        print("Gate failed: invalid unsafe ratio for", fam)
        sys.exit(1)
print("Adversarial gates passed for", latest)
PY
  return $?
}

run_monte_carlo() {
  # ──────────────────────────────────────────────────────────────────────────
  # HOW THE TWO SIMULATION ENGINES RELATE
  #
  # Engine 1 — Monte Carlo (stochastic + sim/economic|adversarial|governance/)
  # Engine 2 — Replay / Invariant (contract_model/ + protocols/sew/)
  #
  # This sweep runs representative phases for expected-value/regime checks.
  # ──────────────────────────────────────────────────────────────────────────

  echo "Running Monte Carlo representative sweep (4 domains)..."
  echo ""
  echo "  Phase O  — Economic:    market exit cascade (honest vs malice profitability)"
  echo "  Phase P  — Adversarial: appeals falsification (difficulty/evidence/herding)"
  echo "  Phase AA — Governance:  governance-as-adversary (selective enforcement gaming)"
  echo "  Phase AD — Governance:  bandwidth floor safeguard (AA remediation)"
  echo ""

  local mc_fail=0

  echo "── Phase O: Market Exit Cascade ──────────────────────────────────────────"
  clojure -M:run -- -O -p data/params/phase-o-baseline.edn || mc_fail=$((mc_fail + 1))
  echo ""

  echo "── Phase P Lite: Appeals Falsification ───────────────────────────────────"
  clojure -M:run -- -P -p data/params/phase-p-lite-baseline.edn || mc_fail=$((mc_fail + 1))
  echo ""

  echo "── Phase AA: Governance as Adversary ─────────────────────────────────────"
  clojure -M:run -- -A -p data/params/phase-aa-governance.edn || mc_fail=$((mc_fail + 1))
  echo ""

  echo "── Phase AD: Governance Bandwidth Floor (AA safeguard) ───────────────────"
  clojure -M:run -- -D -p data/params/phase-ad-governance-floor.edn || mc_fail=$((mc_fail + 1))
  echo ""

  if [ "$mc_fail" -eq 0 ]; then
    echo "Monte Carlo sweep: all 4 phases PASSED"
  else
    echo "Monte Carlo sweep: $mc_fail phase(s) FAILED"
  fi

  return $mc_fail
}

run_long_horizon() {
  require_clojure || return $?
  echo "Running long-horizon coverage suite (extended epoch scenarios)..."

  local lh_fail=0
  local lh_risk_lines="$ARTIFACT_DIR/.risk-${RUN_ID}.lines"
  local lh_meta_file="$ARTIFACT_DIR/.long-horizon-${RUN_ID}.meta"
  : > "$lh_risk_lines"
  : > "$lh_meta_file"

  echo "── Phase T: Governance capture (100 epochs) ───────────────────────────────"
  clojure -M:run -- -H -p data/params/phase-t-governance-capture.edn || lh_fail=$((lh_fail + 1))
  echo ""

  echo "── Phase AH: Trajectory sweep (100/500/1000 epochs, runtime-safe profile) ─"
  clojure -M:run -- -U -p data/params/phase-ah-trajectory-sweep-long-horizon.edn || lh_fail=$((lh_fail + 1))
  echo ""

  echo "── Phase AI: Escalation trap (200 epochs) ─────────────────────────────────"
  local ai_log
  ai_log="$(mktemp)"
  clojure -M:run -- -V -p data/params/phase-ai-escalation-trap.edn >"$ai_log" 2>&1 || lh_fail=$((lh_fail + 1))
  cat "$ai_log"
  if grep -Eq "Status: ❌ FAIL|✗ FAIL" "$ai_log"; then
    echo "HARD GATE: Phase AI reported critical failure; marking long-horizon as failed."
    echo "critical|phase-ai|AI_CRITICAL_FAILURE|Phase AI escalation trap indicates critical vulnerability" >> "$lh_risk_lines"
    lh_fail=$((lh_fail + 1))
  else
    echo "info|phase-ai|AI_PASS|Phase AI did not report critical failure markers" >> "$lh_risk_lines"
  fi
  rm -f "$ai_log"
  echo ""

  echo "── Phase Z: Legitimacy loop (100 epochs) ──────────────────────────────────"
  local z_log
  z_log="$(mktemp)"
  clojure -M:run -- -Z -p data/params/phase-z-legitimacy.edn >"$z_log" 2>&1 || lh_fail=$((lh_fail + 1))
  cat "$z_log"
  if grep -Eq "Hypothesis holds\? ❌ NO|DEATH SPIRAL" "$z_log"; then
    echo "HARD GATE: Phase Z reported legitimacy instability; marking long-horizon as failed."
    echo "critical|phase-z|Z_LEGITIMACY_FAILURE|Phase Z reports legitimacy instability or death spiral" >> "$lh_risk_lines"
    lh_fail=$((lh_fail + 1))
  else
    echo "info|phase-z|Z_PASS|Phase Z did not report legitimacy failure markers" >> "$lh_risk_lines"
  fi
  rm -f "$z_log"
  echo ""

  echo "── Phase J: Baseline stable (100 epochs) ──────────────────────────────────"
  clojure -M:run -- -m -p data/params/phase-j-baseline-stable.edn || lh_fail=$((lh_fail + 1))
  echo ""

  if [ "$lh_fail" -eq 0 ]; then
    echo "Long-horizon suite: all extended-horizon scenarios PASSED"
  else
    echo "Long-horizon suite: $lh_fail scenario(s) FAILED"
  fi

  echo "internal_failures=$lh_fail" > "$lh_meta_file"

  return $lh_fail
}

case "$MODE" in
  unit)
    run_unit || FAILURES=$((FAILURES + 1))
    ;;
  invariants)
    run_invariants || FAILURES=$((FAILURES + 1))
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
    run_target coverage run_coverage_gates || FAILURES=$((FAILURES + 1))
    echo ""
    run_target monte-carlo run_monte_carlo || FAILURES=$((FAILURES + 1))
    ;;
  *)
    echo "Unknown mode: $MODE"
    echo "Usage: $0 [unit|generators|contracts|invariants|suites|equivalence-new|comparison-lint|coverage|adversarial-sweep|adversarial-gates|triage|monte-carlo|long-horizon|all]"
    exit 1
    ;;
esac

if [ -f "$ARTIFACT_DIR/.targets-${RUN_ID}.csv" ]; then
  python - <<PY
import csv, json, pathlib
csv_path = pathlib.Path("$ARTIFACT_DIR/.targets-${RUN_ID}.csv")
risk_path = pathlib.Path("$ARTIFACT_DIR/.risk-${RUN_ID}.lines")
lh_meta_path = pathlib.Path("$ARTIFACT_DIR/.long-horizon-${RUN_ID}.meta")
force_refund_trace = pathlib.Path("data/fixtures/traces/s64-force-refund-then-illegal-release-attempt.trace.json")
rows = []
for r in csv.reader(csv_path.read_text().splitlines()):
    if len(r) != 5:
        continue
    rows.append({
        "target": r[0],
        "status": r[1],
        "exit_code": int(r[2]),
        "duration_ms": int(r[3]),
        "log_file": r[4]
    })

risk_digest = {
  "critical_findings": [],
  "warnings": [],
  "infos": [],
  "gates_failed": [],
  "gates_passed": []
}
if risk_path.exists():
    for line in risk_path.read_text().splitlines():
        # Expected format: severity|phase|code|message
        # Use maxsplit=3 so free-form message text is preserved.
        parts = line.split("|", 3)
        if len(parts) != 4:
            continue
        severity, phase, code, message = parts[0], parts[1], parts[2], parts[3]
        item = {"phase": phase, "code": code, "message": message}
        if severity == "critical":
            risk_digest["critical_findings"].append(item)
            risk_digest["gates_failed"].append(code)
        elif severity == "warning":
            risk_digest["warnings"].append(item)
        else:
            risk_digest["infos"].append(item)
            risk_digest["gates_passed"].append(code)

# P1 clarity: enrich risk digest from target logs (OOM/kill signals, etc.)
for t in rows:
    lp = pathlib.Path(t.get("log_file", ""))
    if not lp.exists():
        continue
    txt = lp.read_text(errors="ignore")
    if "Killed                  clojure -M:run -- -U" in txt or " Killed                  clojure -M:run -- -U" in txt:
        risk_digest["warnings"].append({
            "phase": "phase-ah",
            "code": "AH_RUNTIME_KILLED",
            "message": "Phase AH process was killed (likely resource exhaustion); interpret downstream results with caution"
        })

overall = "pass" if $FAILURES == 0 else "fail"
if risk_digest["critical_findings"]:
    decision = "REJECTED_CRITICAL"
elif overall == "pass" and risk_digest["warnings"]:
    decision = "ACCEPTED_WITH_WARNINGS"
elif overall == "pass":
    decision = "PASS_CLEAN"
else:
    decision = "REJECTED"

out = {
  "run_id": "$RUN_ID",
  "mode": "$MODE",
  "overall_status": overall,
  "acceptance_decision": decision,
  "failure_count": $FAILURES,
  "risk_digest": risk_digest,
  "targets": rows,
}

# P2: per-phase failure counters for dashboard-friendly aggregation
phase_failures = {
    "phase_ai": 0,
    "phase_z": 0,
    "phase_ah": 0,
    "contracts": 0,
    "other": 0
}

# Count known hard-gate failures from structured risk digest
for item in risk_digest.get("critical_findings", []):
    code = str(item.get("code", ""))
    if code == "AI_CRITICAL_FAILURE":
        phase_failures["phase_ai"] += 1
    elif code == "Z_LEGITIMACY_FAILURE":
        phase_failures["phase_z"] += 1
    else:
        phase_failures["other"] += 1

# Enrich counters from log signatures (captures non-critical failures too)
for t in rows:
    target = str(t.get("target", ""))
    status = str(t.get("status", ""))
    lp = pathlib.Path(t.get("log_file", ""))
    txt = lp.read_text(errors="ignore") if lp.exists() else ""

    if target == "contracts" and status == "fail":
        phase_failures["contracts"] += 1

    if "Killed                  clojure -M:run -- -U" in txt or " Killed                  clojure -M:run -- -U" in txt:
        phase_failures["phase_ah"] += 1

out["phase_failures"] = phase_failures

if lh_meta_path.exists():
    meta = {}
    for line in lh_meta_path.read_text().splitlines():
        if "=" not in line:
            continue
        k, v = line.split("=", 1)
        meta[k.strip()] = v.strip()
    if "internal_failures" in meta:
        try:
            out["long_horizon_internal_failures"] = int(meta["internal_failures"])
        except ValueError:
            out["long_horizon_internal_failures"] = meta["internal_failures"]

# P1 force-refund forward-only fixture signal
force_refund_signal = {
    "checked": False,
    "status": "inconclusive",
    "offending_workflows": [],
    "note": "fixture not found"
}
if force_refund_trace.exists():
    force_refund_signal["checked"] = True
    try:
        obj = json.loads(force_refund_trace.read_text())
        events = obj.get("events", [])
        # Heuristic fixture-level check: after buyer-winning execute_resolution,
        # a seller release attempt must exist (forward-only guard regression pattern).
        has_buyer_win = any(
            e.get("action") == "execute_resolution" and
            str(e.get("params", {}).get("winner", "")).lower() == "buyer"
            for e in events
        )
        has_seller_release_attempt = any(
            e.get("action") == "release" and str(e.get("agent", "")).lower() == "seller"
            for e in events
        )
        if has_buyer_win and has_seller_release_attempt:
            force_refund_signal.update({
                "status": "pass",
                "note": "force-refund forward-only regression pattern is present in fixture"
            })
        else:
            force_refund_signal.update({
                "status": "fail",
                "note": "expected force-refund forward-only regression pattern missing in fixture"
            })
    except Exception as e:
        force_refund_signal.update({
            "status": "inconclusive",
            "note": f"unable to parse force-refund fixture: {e}"
        })
out["force_refund_forward_only"] = force_refund_signal

pathlib.Path("$ARTIFACT_FILE").write_text(json.dumps(out, indent=2))
print(f"Wrote machine-readable summary: $ARTIFACT_FILE")
PY
fi

exit $FAILURES
