#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ACTUAL="$ROOT/actual"
EXPECTED="$ROOT/expected"

canon() {
  python3 - <<'PY' "$1"
import json,sys
p=sys.argv[1]
obj=json.load(open(p))
print(json.dumps(obj, separators=(",",":"), sort_keys=True))
PY
}

files=(summary scenario-results invariants economic-results evidence-matrix)
for base in "${files[@]}"; do
  [[ -f "$ACTUAL/$base.json" ]] || { echo "FAIL missing actual file: $base.json"; exit 1; }
  [[ -f "$EXPECTED/$base.json" ]] || { echo "FAIL missing expected file: $base.json"; exit 1; }
  [[ -f "$EXPECTED/$base.sha256" ]] || { echo "FAIL missing expected hash: $base.sha256"; exit 1; }

  a="$(canon "$ACTUAL/$base.json")"
  e="$(canon "$EXPECTED/$base.json")"
  [[ "$a" == "$e" ]] || { echo "FAIL canonical content mismatch: $base.json"; exit 1; }

  ACTUAL_HASH=$(sha256sum "$ACTUAL/$base.json" | awk '{print $1}')
  EXPECTED_HASH=$(tr -d '[:space:]' < "$EXPECTED/$base.sha256")
  [[ "$ACTUAL_HASH" == "$EXPECTED_HASH" ]] || { echo "FAIL hash mismatch: $base.json"; exit 1; }
done

[[ -f "$ACTUAL/traces/001-governance-sandwich.trace.json" ]] || { echo "FAIL missing simulator trace"; exit 1; }
[[ -f "$EXPECTED/traces/001-governance-sandwich.trace.json" ]] || { echo "FAIL missing expected simulator trace"; exit 1; }
[[ -f "$EXPECTED/traces/001-governance-sandwich.trace.sha256" ]] || { echo "FAIL missing expected simulator trace hash"; exit 1; }

A_TRACE=$(sha256sum "$ACTUAL/traces/001-governance-sandwich.trace.json" | awk '{print $1}')
E_TRACE=$(tr -d '[:space:]' < "$EXPECTED/traces/001-governance-sandwich.trace.sha256")
[[ "$A_TRACE" == "$E_TRACE" ]] || { echo "FAIL simulator trace hash mismatch"; exit 1; }

echo "PASS verify-reference-validation-v1"
