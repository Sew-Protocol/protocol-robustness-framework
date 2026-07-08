#!/usr/bin/env python3
"""Forensic run pre-validation: force-authorisation lifecycle consistency.

Reads a forensic run directory or bundle root JSON.
Checks:
  1. If force-authorisation evidence events exist, the bundle root must
     contain :protocol/state-hashes with force-authorisations/hash and
     force-authorisations/consumed-hash.
  2. If :protocol/state-hashes is present, the hash structure must be
     non-empty and well-formed.
  3. Evidence lifecycle consistency: grant events must precede execute
     events for each auth-id; no double-execute of the same auth-id.

Usage:
    python3 scripts/forensic/validate.py <bundle-root.json> [--run-dir <dir>]
    python3 scripts/forensic/validate.py <run-dir>
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

SCHEMA_VERSION = "forensic-validate.v1"

FORCE_AUTH_REASONS = {
    "force-authorisation-granted",
    "force-authorisation-revoked",
    "force-authorisation-executed",
}


def load_json(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text())
    except Exception:
        return None


def find_evidence_files(
    run_dir: Path,
) -> list[dict]:
    """Scan run_dir recursively for event-evidence JSON files containing
    force-authorisation evidence types."""
    results: list[dict] = []
    ev_dir = run_dir / "event-evidence"
    if not ev_dir.is_dir():
        return results
    for f in sorted(ev_dir.iterdir()):
        if not f.name.endswith(".json"):
            continue
        ev = load_json(f)
        if ev is None:
            continue
        etype = ev.get("evidence/type") or ev.get(":evidence/type") or ""
        rtype = ev.get("evidence/reason") or ev.get(":evidence/reason") or ""
        key = str(etype or rtype)
        if key in FORCE_AUTH_REASONS:
            results.append({
                "file": str(f),
                "type": key,
                "auth-id": (ev.get("force-auth/auth-id")
                            or ev.get(":force-auth/auth-id")),
                "seq": ev.get("event/seq") or ev.get(":event/seq") or 0,
            })
    return results


def validate_bundle_root(
    bundle: dict,
) -> list[dict]:
    """Validate the :protocol/state-hashes section of a bundle root.

    Returns a list of check dicts, each with:
      check/key, check/status ("pass"|"fail"|"skip"), check/message
    """
    checks: list[dict] = []
    proto = bundle.get("protocol/state-hashes") or bundle.get(":protocol/state-hashes")

    if proto is None:
        checks.append({
            "check/key": "protocol-state-hashes-present",
            "check/status": "skip",
            "check/message": ":protocol/state-hashes not present in bundle root",
        })
        return checks

    checks.append({
        "check/key": "protocol-state-hashes-present",
        "check/status": "pass",
        "check/message": ":protocol/state-hashes present",
    })

    fa_hash = proto.get("force-authorisations/hash") or proto.get(":force-authorisations/hash")
    fa_consumed_hash = (
        proto.get("force-authorisations/consumed-hash")
        or proto.get(":force-authorisations/consumed-hash")
    )

    if fa_hash:
        checks.append({
            "check/key": "force-authorisations-hash-well-formed",
            "check/status": "pass" if isinstance(fa_hash, str) and len(fa_hash) > 0 else "fail",
            "check/message": f"force-authorisations/hash: {fa_hash[:16] if fa_hash else 'empty'}...",
        })
    else:
        checks.append({
            "check/key": "force-authorisations-hash-well-formed",
            "check/status": "fail",
            "check/message": "force-authorisations/hash is missing or empty",
        })

    if fa_consumed_hash:
        checks.append({
            "check/key": "force-authorisations-consumed-hash-well-formed",
            "check/status": "pass" if isinstance(fa_consumed_hash, str) and len(fa_consumed_hash) > 0 else "fail",
            "check/message": f"force-authorisations/consumed-hash: {fa_consumed_hash[:16] if fa_consumed_hash else 'empty'}...",
        })
    else:
        checks.append({
            "check/key": "force-authorisations-consumed-hash-well-formed",
            "check/status": "fail",
            "check/message": "force-authorisations/consumed-hash is missing or empty",
        })

    return checks


def validate_evidence_lifecycle(
    events: list[dict],
) -> list[dict]:
    """Validate force-authorisation lifecycle from evidence events.

    Checks:
      - Every execute has a preceding grant for the same auth-id.
      - No auth-id is executed twice.
      - Revoked auth-ids are not executed (already caught by
        protocol guards, but evidence-level check adds transparency).
    """
    checks: list[dict] = []
    if not events:
        return checks

    grants: dict[str, int] = {}
    executes: dict[str, list[int]] = {}
    revokes: dict[str, int] = {}

    for ev in events:
        aid = ev.get("auth-id")
        if not aid:
            continue
        seq = ev.get("seq", 0)
        t = ev.get("type", "")
        if t == "force-authorisation-granted":
            grants[aid] = seq
        elif t == "force-authorisation-revoked":
            revokes[aid] = seq
        elif t == "force-authorisation-executed":
            executes.setdefault(aid, []).append(seq)

    for aid, exec_seqs in executes.items():
        grant_seq = grants.get(aid)
        if grant_seq is None:
            checks.append({
                "check/key": f"execute-without-grant-{aid[:20]}",
                "check/status": "fail",
                "check/message": f"auth-id {aid} has execute event but no matching grant",
            })
            continue

        if len(exec_seqs) > 1:
            checks.append({
                "check/key": f"double-execute-{aid[:20]}",
                "check/status": "fail",
                "check/message": f"auth-id {aid} executed {len(exec_seqs)} times",
            })
            continue

        exec_seq = exec_seqs[0]
        if exec_seq < grant_seq:
            checks.append({
                "check/key": f"execute-before-grant-{aid[:20]}",
                "check/status": "fail",
                "check/message": f"auth-id {aid} executed at seq {exec_seq} before grant at seq {grant_seq}",
            })
            continue

        if aid in revokes:
            revoke_seq = revokes[aid]
            if exec_seq > revoke_seq:
                checks.append({
                    "check/key": f"revoke-before-execute-{aid[:20]}",
                    "check/status": "fail",
                    "check/message": f"auth-id {aid} revoked at seq {revoke_seq} but executed at "
                    f"seq {exec_seq} — protocol should prevent this",
                })

    for aid, grant_seq in grants.items():
        if aid not in executes:
            checks.append({
                "check/key": f"grant-without-execute-{aid[:20]}",
                "check/status": "warn",
                "check/message": f"auth-id {aid} was granted but never executed",
            })

    return checks


def run_pre_checks(
    bundle_root_path: Path,
    run_dir: Path | None = None,
) -> dict:
    """Run all pre-checks and return a validation report."""
    bundle = load_json(bundle_root_path)
    if bundle is None:
        return {
            "validate/schema-version": SCHEMA_VERSION,
            "validate/status": "error",
            "validate/errors": [f"Cannot read bundle root: {bundle_root_path}"],
        }

    checks: list[dict] = []

    # Step 1: scan evidence events in run directory
    events: list[dict] = []
    if run_dir and run_dir.is_dir():
        events = find_evidence_files(run_dir)

    force_auth_used = len(events) > 0

    # Step 2: validate bundle root protocol/state-hashes
    bundle_checks = validate_bundle_root(bundle)
    checks.extend(bundle_checks)

    # Step 3: force-auth evidence found but no protocol/state-hashes → fail
    proto_present = any(c["check/key"] == "protocol-state-hashes-present"
                        and c["check/status"] == "pass" for c in bundle_checks)
    if force_auth_used and not proto_present:
        checks.append({
            "check/key": "force-auth-evidence-without-state-hashes",
            "check/status": "fail",
            "check/message": f"Found {len(events)} force-authorisation evidence events but "
            "bundle root has no :protocol/state-hashes",
        })

    # Step 4: evidence lifecycle consistency
    lifecycle_checks = validate_evidence_lifecycle(events)
    checks.extend(lifecycle_checks)

    # Summary
    passed = sum(1 for c in checks if c["check/status"] == "pass")
    failed = sum(1 for c in checks if c["check/status"] == "fail")
    warned = sum(1 for c in checks if c["check/status"] == "warn")
    skipped = sum(1 for c in checks if c["check/status"] == "skip")

    status = "pass" if failed == 0 else "fail"

    return {
        "validate/schema-version": SCHEMA_VERSION,
        "validate/status": status,
        "validate/checks": checks,
        "validate/summary": {
            "passed": passed,
            "failed": failed,
            "warned": warned,
            "skipped": skipped,
        },
        "validate/force-auth-evidence-count": len(events),
    }


def main():
    import argparse

    parser = argparse.ArgumentParser(
        description="Pre-validation for forensic bundle root integrity"
    )
    parser.add_argument("input", help="Path to bundle root JSON or run directory")
    parser.add_argument(
        "--run-dir",
        default=None,
        help="Path to forensic run directory (for evidence scanning)",
    )
    args = parser.parse_args()

    input_path = Path(args.input).expanduser().resolve()

    if input_path.is_dir():
        # Input is a run directory — find the bundle root inside
        bundle_path = input_path / "clojure-bundle-root.json"
        if not bundle_path.exists():
            print(json.dumps({
                "validate/schema-version": SCHEMA_VERSION,
                "validate/status": "error",
                "validate/errors": [f"clojure-bundle-root.json not found in {input_path}"],
            }, indent=2))
            sys.exit(1)
        report = run_pre_checks(bundle_path, run_dir=input_path)
    else:
        # Input is a bundle root JSON file
        run_dir = Path(args.run_dir).expanduser().resolve() if args.run_dir else None
        report = run_pre_checks(input_path, run_dir=run_dir)

    print(json.dumps(report, indent=2))
    sys.exit(0 if report.get("validate/status") == "pass" else 1)


if __name__ == "__main__":
    main()
