#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$(cd "$ROOT/../.." && pwd)"
mkdir -p results/test-artifacts
clojure -M:reference-validation --suite-root "$ROOT" --protocol sew "$@"
