#!/usr/bin/env bash
# Portability Smoke Test for prf-benchmark.jar
# Verifies that the JAR works from any directory without git or repo checkout.
#
# Usage:
#   bash scripts/portability-smoke-test.sh
#
# Returns:
#   0 - all tests pass
#   1 - one or more tests fail

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TEMP_DIR="$(mktemp -d)"
JAR_PATH="${PROJECT_DIR}/target/prf-runner-benchmark-0.1.0-uber.jar"
PASS_COUNT=0
FAIL_COUNT=0

cleanup() {
  rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

pass() { PASS_COUNT=$((PASS_COUNT + 1)); echo "  PASS: $1"; }
fail() { FAIL_COUNT=$((FAIL_COUNT + 1)); echo "  FAIL: $1"; }

# Ensure JAR exists
if [ ! -f "$JAR_PATH" ]; then
  echo "Building benchmark uberjar..."
  (cd "$PROJECT_DIR" && clojure -T:build uberjar :variant benchmark) || {
    echo "ERROR: Failed to build JAR"
    exit 1
  }
fi

echo ""
echo "=== Portability Smoke Test ==="
echo "JAR:  ${JAR_PATH}"
echo "Temp: ${TEMP_DIR}"
echo ""

# Copy JAR to temp dir (simulates running from outside repo)
cp "$JAR_PATH" "$TEMP_DIR/prf-benchmark.jar"
cd "$TEMP_DIR"

# --- Test 1: --list works ---
echo "--- Test 1: --list ---"
java -jar prf-benchmark.jar --list > "$TEMP_DIR/list-out.txt" 2>&1 && {
  grep -q "escrow-dispute" "$TEMP_DIR/list-out.txt" && pass "--list shows expected benchmarks" || fail "--list output missing benchmarks"
} || { fail "--list command failed"; cat "$TEMP_DIR/list-out.txt"; }

# --- Test 2: validate resources ---
echo "--- Test 2: validate resources ---"
java -jar prf-benchmark.jar validate resources > "$TEMP_DIR/validate-resources.txt" 2>&1 && {
  grep -q "PASS" "$TEMP_DIR/validate-resources.txt" && pass "validate resources passes" || fail "validate resources did not pass"
} || { fail "validate resources failed"; cat "$TEMP_DIR/validate-resources.txt"; }

# --- Test 3: validate (all checks) ---
echo "--- Test 3: validate ---"
java -jar prf-benchmark.jar validate > "$TEMP_DIR/validate.txt" 2>&1 && {
  grep -q "PASS" "$TEMP_DIR/validate.txt" && pass "validate passes" || fail "validate did not pass"
} || { fail "validate failed"; cat "$TEMP_DIR/validate.txt"; }

# --- Test 4: doctor ---
echo "--- Test 4: doctor ---"
java -jar prf-benchmark.jar doctor --out "$TEMP_DIR/doctor-out" > "$TEMP_DIR/doctor.txt" 2>&1 && {
  HEALTHY_COUNT=$(grep -c "PASS" "$TEMP_DIR/doctor.txt" || true)
  [ "$HEALTHY_COUNT" -ge 5 ] && pass "doctor healthy (${HEALTHY_COUNT}/6 pass)" || fail "doctor: only ${HEALTHY_COUNT}/6 pass"
} || { fail "doctor failed"; cat "$TEMP_DIR/doctor.txt"; }

# --- Test 5: verify-portability ---
echo "--- Test 5: verify-portability ---"
java -jar prf-benchmark.jar verify-portability --out "$TEMP_DIR/verify-out" > "$TEMP_DIR/verify.txt" 2>&1 && {
  grep -q "PASSED" "$TEMP_DIR/verify.txt" && pass "verify-portability passes" || fail "verify-portability did not pass"
} || { fail "verify-portability failed"; cat "$TEMP_DIR/verify.txt"; }

# --- Test 6: list game-theory-checks ---
echo "--- Test 6: list game-theory-checks ---"
java -jar prf-benchmark.jar list game-theory-checks > "$TEMP_DIR/gt-list.txt" 2>&1 && {
  grep -q "Mechanism properties" "$TEMP_DIR/gt-list.txt" && pass "list game-theory-checks works" || fail "list game-theory-checks output wrong"
} || { fail "list game-theory-checks failed"; cat "$TEMP_DIR/gt-list.txt"; }

# --- Test 7: explain game-theory ---
echo "--- Test 7: explain game-theory ---"
java -jar prf-benchmark.jar explain game-theory > "$TEMP_DIR/gt-explain.txt" 2>&1 && {
  grep -q "equilibrium validation" "$TEMP_DIR/gt-explain.txt" && pass "explain game-theory works" || fail "explain game-theory output wrong"
} || { fail "explain game-theory failed"; cat "$TEMP_DIR/gt-explain.txt"; }

# --- Test 8: No source-tree paths in outputs ---
echo "--- Test 8: source-tree path check ---"
if grep -r "/home/user/Code/\|/workspaces/" "$TEMP_DIR" --include="*.txt" --include="*.edn" --include="*.json" 2>/dev/null; then
  fail "Outputs contain source-tree paths"
else
  pass "No source-tree paths in outputs"
fi

# --- Test 9: doctor report file exists ---
echo "--- Test 9: doctor report ---"
DOCTOR_REPORT=$(find "$TEMP_DIR/doctor-out" -name "doctor-report.edn" 2>/dev/null | head -1)
[ -n "$DOCTOR_REPORT" ] && [ -s "$DOCTOR_REPORT" ] && pass "Doctor report exists" || fail "Doctor report missing"

# --- Summary ---
echo ""
echo "=== Results: ${PASS_COUNT} passed, ${FAIL_COUNT} failed ==="
[ "$FAIL_COUNT" -gt 0 ] && exit 1 || exit 0
