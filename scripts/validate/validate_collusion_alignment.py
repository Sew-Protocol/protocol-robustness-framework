#!/usr/bin/env python3
"""Validate collusion fixture/claim alignment.

Extracted from test.sh inline Python block at line 445.
"""

import json
import sys
from pathlib import Path


def main():
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
    return 0


if __name__ == "__main__":
    sys.exit(main())
