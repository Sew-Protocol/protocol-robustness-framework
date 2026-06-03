#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$(cd "$ROOT/../.." && pwd)"
clojure -M:reference-validation "$@"
