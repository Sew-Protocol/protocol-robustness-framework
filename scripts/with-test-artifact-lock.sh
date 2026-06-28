#!/usr/bin/env bash
# Acquire an exclusive flock on results/.test-artifact.lock, then execute the
# provided command.  Blocks until the lock is available.  Auto-releases when
# the locked process exits or dies.
#
# Prevents cross-process clobbering of results/test-artifacts/ and adjacent
# shared test output paths when multiple CI jobs or ad-hoc tasks write to
# them concurrently (e.g. nb-ok vs test:notebooks vs test:framework).
#
# Does NOT address:
#   - Slow scenario replay times (SPEDS, invariants).
#   - In-JVM alter-var-root global mutation hazards in test code.
#
# Usage:
#   scripts/with-test-artifact-lock.sh <command> [args...]
#
# Example:
#   scripts/with-test-artifact-lock.sh clojure -M:with-sew -e '(println "hi")'
#
# The wrapped command's exit code is preserved.

set -euo pipefail

LOCK_FILE="results/.test-artifact.lock"

mkdir -p "$(dirname "${LOCK_FILE}")"

exec flock "${LOCK_FILE}" "$@"
