#!/usr/bin/env python3
"""
Write lightweight run-manifest artifacts for a bb scenario:run,
scenario:run:family, or evidence:build invocation.

Produces into results/test-artifacts/:
  test-run.json                 schema: test-run.v1
  test-summary.json             schema: test-summary.v2
  claimable-classification.json schema: claimable-classification.v1
  test-artifacts.json           schema: test-artifacts.v1

Only artifacts produced by this invocation are registered.
Pre-existing coverage/findings/issues from prior test.sh runs are
intentionally excluded to avoid stale-sha256 entries.
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import pathlib
import platform
import re
import subprocess
import sys

SCENARIOS_DIR = pathlib.Path("scenarios")


# ── file helpers ──────────────────────────────────────────────────────────────

def sha256_file(path: pathlib.Path) -> str | None:
    if not path.exists():
        return None
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()


def artifact_meta(path: pathlib.Path) -> dict | None:
    if not path.exists():
        return None
    st = path.stat()
    return {
        "sha256": sha256_file(path),
        "bytes": st.st_size,
        "mtime_utc": datetime.datetime.fromtimestamp(
            st.st_mtime, datetime.timezone.utc
        ).isoformat(),
    }


def mk_artifact_entry(
    aid: str,
    kind: str,
    path_s: str,
    schema_version: str,
    producer: str,
    verifies_against: list,
    input_versions: dict | None = None,
) -> dict | None:
    p = pathlib.Path(path_s)
    m = artifact_meta(p)
    if not m:
        return None
    return {
        "id": aid,
        "kind": kind,
        "path": path_s,
        "schema_version": schema_version,
        "contract_version": "evidence-contract.v1",
        "producer": producer,
        "verifies_against": verifies_against,
        "input_versions": input_versions or {},
        **m,
    }


# ── environment probes ────────────────────────────────────────────────────────

def get_git_sha() -> str | None:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], text=True, stderr=subprocess.DEVNULL
        ).strip()
    except Exception:
        return None


def get_java_version() -> str | None:
    try:
        out = subprocess.check_output(
            ["java", "-version"], text=True, stderr=subprocess.STDOUT
        )
        return out.splitlines()[0].strip() if out.strip() else None
    except Exception:
        return None


# ── capability scan (mirrors test.sh logic) ───────────────────────────────────

def scenario_capabilities_summary(scenarios_dir: pathlib.Path) -> dict:
    profiles: set[str] = set()
    archetypes: set[str] = set()
    module_statuses: set[str] = set()
    liquidity_modes: set[str] = set()
    failure_modes: set[str] = set()
    required_capabilities: set[str] = set()
    actor_abilities_present = 0
    yield_enabled = 0
    yield_disabled = 0
    shortfall_related = 0
    partial_liquidity_enabled = 0

    for p in sorted(scenarios_dir.glob("*.json")):
        try:
            obj = json.loads(p.read_text())
        except Exception:
            continue
        if not isinstance(obj, dict):
            continue

        for c in (obj.get("required_capabilities") or []):
            required_capabilities.add(str(c))
        if obj.get("actor_abilities"):
            actor_abilities_present += 1

        ycfg = obj.get("yield_config") or {}
        modules = (ycfg.get("modules") or {}) if isinstance(ycfg, dict) else {}
        if not isinstance(modules, dict):
            modules = {}
        if modules:
            yield_enabled += 1
        else:
            yield_disabled += 1

        for module_id, module_cfg in modules.items():
            profiles.add(str(module_id))
            if isinstance(module_cfg, dict):
                module_statuses.add(str(module_cfg.get("module_status", "active")))
                tokens = module_cfg.get("tokens") or {}
                if not isinstance(tokens, dict):
                    tokens = {}
                for _, token_cfg in tokens.items():
                    if not isinstance(token_cfg, dict):
                        continue
                    lm = str(token_cfg.get("liquidity_mode", "available"))
                    liquidity_modes.add(lm)
                    fms = [str(x) for x in (token_cfg.get("failure_modes") or [])]
                    for fm in fms:
                        failure_modes.add(fm)
                    if lm == "shortfall" or "partial-liquidity" in fms:
                        shortfall_related += 1
                    if "partial-liquidity" in fms:
                        partial_liquidity_enabled += 1

        pp = obj.get("protocol_params") or {}
        if not isinstance(pp, dict):
            pp = {}
        yid = pp.get("yield_generation_module")
        if yid:
            profiles.add(str(yid))
            if str(yid) == "aave-v3":
                archetypes.add("yield.provider/liquid-lending")

    return {
        "yield": {
            "enabled": yield_enabled > 0,
            "profile_ids": sorted(profiles),
            "archetypes": sorted(archetypes),
            "module_statuses": sorted(module_statuses),
            "liquidity_modes": sorted(liquidity_modes),
            "failure_modes": sorted(failure_modes),
            "enabled_scenarios": yield_enabled,
            "disabled_scenarios": yield_disabled,
            "shortfall_related_scenarios": shortfall_related,
            "partial_liquidity_enabled_scenarios": partial_liquidity_enabled,
        },
        "required_capabilities_seen": sorted(required_capabilities),
        "actor_abilities_scenarios": actor_abilities_present,
    }


# ── static claimable classification (mirrors test.sh) ────────────────────────

CLAIMABLE_CLASSIFICATION: dict = {
    "schema_version": "claimable-classification.v1",
    "shortfall_policy": {
        "mode": "partial-liquidity-supported",
        "allocation": "fulfilled-plus-deferred",
        "rounding_policy": "floor-to-asset-decimals.v1",
    },
    "classes": {
        "escrow_principal": {
            "delivery_model": "pull",
            "source": "settlement",
            "recipient_type": "party",
            "risk_class": "user-withdrawable",
        },
        "escrow_yield": {
            "delivery_model": "pull",
            "source": "yield",
            "recipient_type": "party-or-protocol",
            "risk_class": "yield-derived",
            "shortfall_outcome": "may-be-partially-deferred",
        },
        "resolver_payment": {
            "delivery_model": "pull",
            "source": "dispute-resolution",
            "recipient_type": "resolver",
            "risk_class": "service-compensation",
        },
        "bond_refund": {
            "delivery_model": "pull",
            "source": "appeal-bond",
            "recipient_type": "disputant",
            "risk_class": "bond-return",
        },
        "protocol_fee": {
            "delivery_model": "pull-or-governance-withdrawal",
            "source": "fee",
            "recipient_type": "protocol",
            "risk_class": "protocol-revenue",
        },
    },
}


# ── main ──────────────────────────────────────────────────────────────────────

def make_scenario_slug(scenario: str, suite: str) -> str:
    """Derive a short filesystem-safe slug from the scenario path or selector."""
    if suite == "scenario-family":
        raw = f"fam-{scenario}"
    elif suite == "evidence":
        raw = f"ev-{pathlib.Path(scenario).stem}"
    else:
        raw = pathlib.Path(scenario).stem
    slug = re.sub(r"[^a-zA-Z0-9-]", "-", raw).strip("-")
    slug = re.sub(r"-+", "-", slug)
    return slug[:50]


def build_suite_block(args: argparse.Namespace) -> dict:
    suite: dict = {"id": args.suite, "version": "suite.v1"}
    if args.suite == "scenario-family":
        suite["selector"] = args.scenario
        suite["scenario_count"] = args.scenario_count
    else:
        suite["scenario"] = args.scenario
    return suite


def write_artifacts_to(
    artifact_dir: pathlib.Path,
    args: argparse.Namespace,
    run_id: str,
    created_at: str,
    git_sha: str | None,
    caps: dict,
) -> None:
    """Write all 4 manifest artifacts into artifact_dir with self-consistent paths."""
    artifact_dir.mkdir(parents=True, exist_ok=True)

    artifact_file     = artifact_dir / "test-summary.json"
    run_manifest_file = artifact_dir / "test-run.json"
    claimable_file    = artifact_dir / "claimable-classification.json"
    registry_file     = artifact_dir / "test-artifacts.json"

    artifacts_map: dict = {
        "test_summary":             str(artifact_file),
        "test_artifacts":           str(registry_file),
        "claimable_classification": str(claimable_file),
    }
    if args.output_file:
        artifacts_map["scenario_result"] = args.output_file

    run_manifest: dict = {
        "schema_version":   "test-run.v1",
        "contract_version": "evidence-contract.v1",
        "produced_by":      {"name": "test-run-emitter", "version": "v1"},
        "run_id":           run_id,
        "created_at":       created_at,
        "triggered_by":     "local/bb",
        "environment": {
            "os":     platform.platform(),
            "python": sys.version.split()[0],
            "java":   get_java_version(),
        },
        "duration_ms": args.duration_ms,
        "framework": {
            "name":       "sew-simulation-test-runner",
            "version":    "0.1.0",
            "git_commit": git_sha,
        },
        "model": {
            "id":         "sew",
            "version":    "sew-model.v1",
            "git_commit": git_sha,
        },
        "suite": build_suite_block(args),
        "capabilities_resolved": {
            "yield":               caps["yield"],
            "withdrawals":         {"enabled": True, "delivery_model": "pull"},
            "dispute_resolution":  {"enabled": True},
            "module_snapshotting": {"enabled": True},
            "settlement_delivery": {"enabled": True, "mode": "pull-claimable"},
            "invariants":          {"enabled": True},
            "projection":          {"enabled": True},
        },
        "artifacts": artifacts_map,
    }

    overall  = args.status
    decision = "PASS_CLEAN" if overall == "pass" else "REJECTED"
    summary: dict = {
        "schema_version":      "test-summary.v2",
        "run_id":              run_id,
        "mode":                args.suite,
        "overall_status":      overall,
        "acceptance_decision": decision,
        "failure_count":       0 if overall == "pass" else 1,
        "risk_digest": {
            "critical_findings": [],
            "warnings":          [],
            "infos":             [],
            "gates_failed":      [],
            "gates_passed":      [],
        },
        "targets": [{
            "target":      args.suite,
            "status":      overall,
            "exit_code":   0 if overall == "pass" else 1,
            "duration_ms": args.duration_ms,
            "log_file":    "",
            "scenario":    args.scenario,
        }],
        "run_manifest": {
            "schema_version": "test-run.v1",
            "path":           str(run_manifest_file),
            "run_id":         run_id,
        },
        "yield_context": caps["yield"],
        "shortfall_exposure": {
            "shortfall_related_scenarios":         caps["yield"].get("shortfall_related_scenarios", 0),
            "partial_liquidity_enabled_scenarios": caps["yield"].get("partial_liquidity_enabled_scenarios", 0),
            "rounding_policy":                     "floor-to-asset-decimals.v1",
        },
        "claimable_classification": {
            "schema_version": CLAIMABLE_CLASSIFICATION["schema_version"],
            "path":           str(claimable_file),
        },
        "status_counts": {
            "targets": {
                "total":   1,
                "pass":    1 if overall == "pass" else 0,
                "fail":    0 if overall == "pass" else 1,
                "unknown": 0,
            },
            "risk":  {"critical": 0, "warning": 0, "info": 0},
            "gates": {"failed": 0,   "passed": 0},
        },
        "phase_failures": {
            "phase_ai": 0, "phase_z": 0, "phase_ah": 0, "contracts": 0, "other": 0,
        },
        "force_refund_forward_only": {
            "checked":             False,
            "status":              "inconclusive",
            "offending_workflows": [],
            "note":                "not checked in scenario mode",
        },
    }

    artifact_file.write_text(json.dumps(summary, indent=2))
    run_manifest_file.write_text(json.dumps(run_manifest, indent=2))
    claimable_file.write_text(json.dumps(CLAIMABLE_CLASSIFICATION, indent=2))

    rm_meta = artifact_meta(run_manifest_file) or {}
    entries = [
        mk_artifact_entry(
            "test-summary", "summary", str(artifact_file),
            summary["schema_version"], "summary-emitter.v1",
            ["test-run.v1", "projection.v1"],
            {"test_run": "test-run.v1", "projection": "projection.v1", "scenario": "scenario.v1"},
        ),
        mk_artifact_entry(
            "test-run", "run-manifest", str(run_manifest_file),
            run_manifest["schema_version"], "test-run-emitter.v1",
            [], {},
        ),
        mk_artifact_entry(
            "claimable-classification", "classification", str(claimable_file),
            CLAIMABLE_CLASSIFICATION["schema_version"], "claimable-classification-emitter.v1",
            ["test-run.v1"], {"test_run": "test-run.v1"},
        ),
    ]

    if args.output_file:
        e = mk_artifact_entry(
            "scenario-result", "scenario-result", args.output_file,
            "scenario-result.v1", "evidence-build-emitter.v1",
            ["test-run.v1"], {"test_run": "test-run.v1"},
        )
        if e:
            entries.append(e)

    entries = [e for e in entries if e is not None]

    registry: dict = {
        "schema_version":   "test-artifacts.v1",
        "contract_version": "evidence-contract.v1",
        "run_id":           run_id,
        "generated_at":     created_at,
        "generator":        {"name": "artifact-registry-emitter", "version": "v1"},
        "root_dir":         str(artifact_dir),
        "run_manifest": {
            "path":           str(run_manifest_file),
            "schema_version": run_manifest["schema_version"],
            **rm_meta,
        },
        "artifacts": entries,
    }
    registry_file.write_text(json.dumps(registry, indent=2))


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Write run-manifest artifacts for a bb scenario run."
    )
    ap.add_argument("--scenario", required=True, help="Scenario path or family selector")
    ap.add_argument(
        "--suite",
        default="scenario",
        choices=["scenario", "scenario-family", "evidence"],
        help="Suite type written into run_manifest.suite.id",
    )
    ap.add_argument(
        "--scenario-count", type=int, default=1, dest="scenario_count",
        help="Number of scenarios run (family mode only)",
    )
    ap.add_argument(
        "--status", default="pass", choices=["pass", "fail"],
        help="Overall pass/fail result for this invocation",
    )
    ap.add_argument("--duration-ms", type=int, default=0, dest="duration_ms")
    ap.add_argument(
        "--output-file", dest="output_file",
        help="Scenario result file written by evidence:build (registered if present)",
    )
    ap.add_argument(
        "--artifact-dir", default="results/test-artifacts", dest="artifact_dir",
        help="Latest/canonical artifact directory (updated after every run)",
    )
    args = ap.parse_args()

    now        = datetime.datetime.now(datetime.timezone.utc)
    run_id     = now.strftime("%Y%m%d-%H%M%S")
    created_at = now.isoformat()
    git_sha    = get_git_sha()
    caps       = scenario_capabilities_summary(SCENARIOS_DIR)

    slug        = make_scenario_slug(args.scenario, args.suite)
    per_run_dir = pathlib.Path("results/runs") / f"{slug}-{run_id}"
    latest_dir  = pathlib.Path(args.artifact_dir)

    # individual immutable record for this invocation
    write_artifacts_to(per_run_dir, args, run_id, created_at, git_sha, caps)
    # mutable "latest" pointer for backward-compat with validate_artifact_registry.py
    write_artifacts_to(latest_dir, args, run_id, created_at, git_sha, caps)

    print(f"[scenario-run-manifest] run_id={run_id} status={args.status} suite={args.suite}")
    print(f"[scenario-run-manifest] per-run : {per_run_dir}")
    print(f"[scenario-run-manifest] latest  : {latest_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

