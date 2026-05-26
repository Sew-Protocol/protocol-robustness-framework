#!/bin/bash
set -e

# Usage: ./scripts/sim_diff.sh --baseline <commit-hash> --scenario <path> [--protocol <id>]

BASELINE=""
SCENARIO=""
PROTOCOL="sew-v1"

while [[ $# -gt 0 ]]; do
  case $1 in
    --baseline) BASELINE="$2"; shift 2 ;;
    --scenario) SCENARIO="$2"; shift 2 ;;
    --protocol) PROTOCOL="$2"; shift 2 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

if [[ -z "$BASELINE" || -z "$SCENARIO" ]]; then
  echo "Usage: ./scripts/sim_diff.sh --baseline <commit-hash> --scenario <path> [--protocol <id>]"
  exit 1
fi

TEMP_DIR=$(mktemp -d)
echo "Checking out baseline $BASELINE to $TEMP_DIR..."
git worktree add -d "$TEMP_DIR" "$BASELINE"

cleanup() {
  echo "Cleaning up worktree..."
  git worktree remove "$TEMP_DIR"
}
trap cleanup EXIT

echo "Running baseline simulation..."
(cd "$TEMP_DIR" && clojure -M:run -- --invariants --scenario "$SCENARIO" --protocol "$PROTOCOL" --output-file results/baseline.json)

echo "Running candidate simulation..."
clojure -M:run -- --invariants --scenario "$SCENARIO" --protocol "$PROTOCOL" --output-file results/candidate.json

echo "Generating comparison report..."
python3 python/trace_compare.py --baseline "$TEMP_DIR/results/baseline.json" --candidate results/candidate.json --out-dir results/sim-diff

echo ""
cat results/sim-diff/comparison.md
