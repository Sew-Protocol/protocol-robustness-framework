#!/usr/bin/env python3
"""Write the concise, machine-readable result for scripts/test.sh."""

from __future__ import annotations

import csv
import json
import pathlib
import sys
from datetime import datetime, timezone


def main(argv: list[str]) -> int:
    if len(argv) != 8:
        print(
            "usage: generate_test_summary.py ARTIFACT_DIR RUN_ID FAILURES MODE "
            "SUMMARY_FILE RUN_MANIFEST_FILE REGISTRY_FILE CLAIMABLE_FILE",
            file=sys.stderr,
        )
        return 2

    (
        artifact_dir_s,
        run_id,
        failures_s,
        mode,
        summary_file_s,
        _run_manifest_file,
        _registry_file,
        _claimable_file,
    ) = argv
    artifact_dir = pathlib.Path(artifact_dir_s)
    summary_file = pathlib.Path(summary_file_s)
    failures = int(failures_s)
    targets_file = artifact_dir / f".targets-{run_id}.csv"

    targets = []
    if targets_file.exists():
        with targets_file.open(newline="", encoding="utf-8") as handle:
            for target, status, exit_code, duration_ms, log_file in csv.reader(handle):
                targets.append(
                    {
                        "target": target,
                        "status": status,
                        "exit_code": int(exit_code),
                        "duration_ms": int(duration_ms),
                        "log_file": log_file,
                    }
                )

    summary = {
        "schema_version": "test-summary.v2",
        "run_id": run_id,
        "mode": mode,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "overall_status": "pass" if failures == 0 else "fail",
        "failure_count": failures,
        "target_count": len(targets),
        "targets": targets,
        "target_index": str(targets_file),
    }
    summary_file.parent.mkdir(parents=True, exist_ok=True)
    summary_file.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote machine-readable test summary: {summary_file}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
