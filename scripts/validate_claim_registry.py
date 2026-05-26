#!/usr/bin/env python3
"""Validate claim registry integrity against scenario and invariant references."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


def fail(msg: str) -> None:
    print(f"[claim-registry] FAIL: {msg}")
    raise SystemExit(1)


def warn(msg: str) -> None:
    print(f"[claim-registry] WARN: {msg}")


def load_registry_json() -> dict:
    """Read registry claims/mappings/invariants via Clojure to avoid EDN parsing in Python."""
    cmd = [
        "clojure",
        "-M",
        "-e",
        (
            "(require '[clojure.data.json :as json]"
            " '[resolver-sim.definitions.registry :as defs])"
            "(println (json/write-str "
            "{:claims (into {} (map (fn [[k v]] [(name k) (assoc v :claim/id (name (:claim/id v)))]) defs/claims))"
            " :claim-scenario-map (into {} (map (fn [[k v]] [(name k) v]) defs/claim-scenario-map))"
            " :invariants (into #{} (map (comp name key)) defs/invariants)}))"
        ),
    ]
    proc = subprocess.run(cmd, check=False, capture_output=True, text=True)
    if proc.returncode != 0:
        fail(f"could not load Clojure registry data: {proc.stderr.strip()}")
    txt = proc.stdout.strip().splitlines()
    if not txt:
        fail("empty registry payload from Clojure")
    return json.loads(txt[-1])


def normalize_claim_id(raw: str) -> str:
    s = str(raw).strip()
    if s.startswith(":"):
        s = s[1:]
    return s


def collect_theory_claim_ids(scenarios_dir: Path) -> set[str]:
    found: set[str] = set()
    for p in sorted(scenarios_dir.glob("*.json")):
        try:
            obj = json.loads(p.read_text())
        except Exception:
            continue
        if not isinstance(obj, dict):
            continue
        theory = obj.get("theory") or {}
        cid = theory.get("claim-id")
        if cid:
            found.add(normalize_claim_id(cid))
    return found


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenarios-dir", default="scenarios")
    ap.add_argument("--strict-theory-claims", action="store_true")
    args = ap.parse_args()

    scenarios_dir = Path(args.scenarios_dir)
    if not scenarios_dir.exists():
        fail(f"scenarios dir missing: {scenarios_dir}")

    payload = load_registry_json()
    claims: dict = payload.get("claims") or {}
    claim_map: dict = payload.get("claim-scenario-map") or {}
    invariants: set[str] = set(payload.get("invariants") or [])

    if not claims:
        fail("registry claims map is empty")

    if set(claims.keys()) != set(claim_map.keys()):
        missing_in_map = sorted(set(claims.keys()) - set(claim_map.keys()))
        extra_in_map = sorted(set(claim_map.keys()) - set(claims.keys()))
        fail(
            "claims and claim-scenario-map keys differ; "
            f"missing_in_map={missing_in_map}, extra_in_map={extra_in_map}"
        )

    scenario_files = {p.name for p in scenarios_dir.glob("*.json")}

    for cid, cdef in claims.items():
        claim_id_field = normalize_claim_id(cdef.get("claim/id") or cid)
        if claim_id_field != cid:
            fail(f"claim/id mismatch for {cid}: claim/id={claim_id_field}")

        rel_invs = cdef.get("claim/related-invariants") or []
        for inv in rel_invs:
            inv_name = str(inv).lstrip(":")
            if inv_name not in invariants:
                fail(f"unknown related invariant '{inv}' for claim '{cid}'")

        entry = claim_map.get(cid) or {}
        for bucket in ("supporting", "falsifying"):
            scenarios = entry.get(bucket) or []
            for sid in scenarios:
                fname = f"{sid}.json"
                if fname not in scenario_files:
                    fail(f"unknown scenario '{sid}' referenced by claim '{cid}' ({bucket})")

    theory_claim_ids = collect_theory_claim_ids(scenarios_dir)
    registry_ids = set(claims.keys())
    unregistered_theory_claims = sorted(theory_claim_ids - registry_ids)

    if unregistered_theory_claims:
        msg = (
            "theory claim IDs found in scenarios but not in registry: "
            + ", ".join(unregistered_theory_claims)
        )
        if args.strict_theory_claims:
            fail(msg)
        warn(msg)

    print("[claim-registry] PASS: claims/scenarios/invariants integrity checks succeeded")
    return 0


if __name__ == "__main__":
    sys.exit(main())
