#!/usr/bin/env bash
# cleanup.sh — Remove development artifacts and archive stale docs
#
# Usage:
#   ./scripts/cleanup.sh          # dry run — show what would change
#   ./scripts/cleanup.sh --apply  # apply all deletions and moves

set -euo pipefail

APPLY=false
if [[ "${1:-}" == "--apply" ]]; then
  APPLY=true
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
NC='\033[0m'

action_delete() {
  local file="$1"
  if [[ -e "$file" ]]; then
    if $APPLY; then
      rm -rf "$file"
      echo -e "  ${RED}deleted${NC}  $file"
    else
      echo -e "  ${RED}delete${NC}   $file"
    fi
  fi
}

action_move() {
  local src="$1" dst="$2"
  if [[ -e "$src" ]]; then
    if $APPLY; then
      mkdir -p "$(dirname "$dst")"
      git mv "$src" "$dst" 2>/dev/null || mv "$src" "$dst"
      echo -e "  ${YELLOW}moved${NC}    $src  →  $dst"
    else
      echo -e "  ${YELLOW}move${NC}     $src  →  $dst"
    fi
  fi
}

echo ""
echo -e "${CYAN}══════════════════════════════════════════${NC}"
if $APPLY; then
  echo -e "${CYAN}  SEW Simulation — Cleanup (APPLYING)${NC}"
else
  echo -e "${CYAN}  SEW Simulation — Cleanup (DRY RUN)${NC}"
  echo -e "${CYAN}  Run with --apply to execute changes${NC}"
fi
echo -e "${CYAN}══════════════════════════════════════════${NC}"

# ── 1. Python scratch files ────────────────────────────────────────────────────
# These are development-session artifacts: one-off gRPC probes, key-marshalling
# experiments, and incremental debug scripts. None are imported by any other
# module. They can all be regenerated from context if needed.

echo ""
echo -e "${GREEN}[1] Python scratch / debug files${NC}"

for f in \
  python/debug_test.py \
  python/debug_test2.py \
  python/debug_test3.py \
  python/debug_test4.py \
  python/debug_test5.py \
  python/test_b1_final.py \
  python/test_b1_final2.py \
  python/test_b1_final3.py \
  python/test_debug2.py \
  python/test_debug_keys.py \
  python/test_dispatch.py \
  python/test_final.py \
  python/test_fix.py \
  python/test_full.py \
  python/test_hyphen.py \
  python/test_int.py \
  python/test_keys.py \
  python/test_keys2.py \
  python/test_marsh.py \
  python/test_pending.py \
  python/test_prober.py \
; do
  action_delete "$f"
done

# ── 2. Ephemeral log / process files ──────────────────────────────────────────
# Left over from manual gRPC server runs. Not needed in the repo.

echo ""
echo -e "${GREEN}[2] Log / process artefacts (root)${NC}"

action_delete "grpc-server.log"
action_delete "grpc-server-smoke.log"
action_delete "nohup.out"

# ── 3. Root-level session artefact ────────────────────────────────────────────
# schema_alignment_report.md was generated during a single dev session to
# compare CDRS schemas. Its content is superseded by docs/CDRS-v1.1-THEORY-SCHEMA.md.

echo ""
echo -e "${GREEN}[3] Root-level session artefact${NC}"

action_delete "schema_alignment_report.md"

# ── 4. Stale docs → archived/ ─────────────────────────────────────────────────
# These documents are no longer part of the active research narrative.
# Moving to archived/ rather than deleting preserves the git history
# context without polluting docs/.

echo ""
echo -e "${GREEN}[4] Stale docs  →  archived/${NC}"

# Internal task-tracking notes: superseded by GitHub Issues / todo tracking.
action_move "docs/tasks"              "archived/docs/tasks"

# AI prompt inputs used during canonical model design sessions. Not reference
# material; confusing to a reviewer browsing docs/.
action_move "docs/canonical_model"   "archived/docs/canonical_model"

# Adversarial review prompt used as an LLM input during a dev session.
action_move "docs/adversarial-prompt.md" "archived/docs/adversarial-prompt.md"

# Internal clarity review generated during a dev session (2026-05-01).
# Its findings have been acted on; the doc itself adds noise for reviewers.
action_move "docs/REPORT_CLARITY_REVIEW.md" "archived/docs/REPORT_CLARITY_REVIEW.md"

# Explicitly self-labelled "Archived Roadmap". Moving it makes that obvious
# from the directory structure rather than requiring readers to open it.
action_move "docs/roadmap-generalisation.md" "archived/docs/roadmap-generalisation.md"

# ── Summary ───────────────────────────────────────────────────────────────────

echo ""
echo -e "${CYAN}══════════════════════════════════════════${NC}"
if $APPLY; then
  echo -e "${GREEN}  Done.${NC}"
else
  echo -e "${YELLOW}  Dry run complete — no files changed.${NC}"
  echo -e "${YELLOW}  Rerun with --apply to execute.${NC}"
fi
echo -e "${CYAN}══════════════════════════════════════════${NC}"
echo ""
