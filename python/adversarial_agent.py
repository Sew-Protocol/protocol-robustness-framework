import json
import os
import uuid
import time
import random
import grpc
import argparse
from collections import defaultdict


def _choose_action(active_ids, valid_bias=0.7):
    """Choose action with a bias toward state-valid transitions.

    - If no active escrow ids: prefer create_escrow.
    - If there are active ids: mostly choose workflow actions, sometimes random invalid probe.
    """
    if not active_ids:
        return "create_escrow"

    valid_actions = ["raise_dispute", "release", "sender_cancel"]
    random_actions = ["create_escrow", "raise_dispute", "release", "sender_cancel"]
    if random.random() < valid_bias:
        return random.choice(valid_actions)
    return random.choice(random_actions)


def _extract_candidate_id(response, fallback_ids):
    """Try to infer a workflow id from response payload fields."""
    trace_entry = response.get("trace_entry") or {}
    extra = trace_entry.get("extra") or {}
    params = trace_entry.get("params") or {}

    for source in (extra, params, trace_entry):
        for key in ("id", "workflow_id", "workflow-id"):
            if key in source:
                return source[key]

    return (fallback_ids[0] if fallback_ids else 0)


def _weighted_choice(candidates, rejection_counts, active_ids):
    """Pick candidate with simple bandit-style weighting.

    Boosts:
      - actions on existing active workflow ids
      - raise_dispute on active ids
    Penalties:
      - recently rejected (actor, action, id) tuples
    """
    if not candidates:
        return None

    weighted = []
    for c in candidates:
        actor = c.get("actor_id", "buyer")
        action = c.get("action")
        params = c.get("params", {}) or {}
        wf_id = params.get("id")

        score = 1.0
        if wf_id in active_ids:
            score += 0.75
        if action == "raise_dispute" and wf_id in active_ids:
            score += 0.5
        if action == "create_escrow" and len(active_ids) > 2:
            score -= 0.25

        key = (actor, action, wf_id)
        score -= min(0.8, 0.2 * rejection_counts[key])
        weighted.append((max(0.05, score), c))

    total = sum(w for w, _ in weighted)
    r = random.random() * total
    acc = 0.0
    for w, c in weighted:
        acc += w
        if acc >= r:
            return c
    return weighted[-1][1]


def _objective_guided_choice(
    candidates,
    rejection_counts,
    active_ids,
    evaluate_objective,
    session_id,
    objective_actor,
    objective_name,
):
    """Select candidate using objective-guided sampling plus prior weighting.

    Keep Clojure as objective authority by querying EvaluateAttackObjective.
    """
    if not candidates:
        return None, None

    base_choice = _weighted_choice(candidates, rejection_counts, active_ids)
    before = evaluate_objective(
        {
            "session_id": session_id,
            "actor_id": objective_actor,
            "objective": objective_name,
        }
    )
    before_score = before.get("score") if before.get("ok") else None

    # With current server surfaces we do not have dry-run transition scoring yet;
    # keep a hook by preferring weighted choice and returning current score.
    return base_choice, before_score


def _apply_suppression(candidates, suppressed_until, seq):
    """Filter out temporarily suppressed (actor, action, id) tuples."""
    filtered = []
    for c in candidates:
        actor = c.get("actor_id", "buyer")
        action = c.get("action")
        wf_id = (c.get("params") or {}).get("id")
        if seq < suppressed_until[(actor, action, wf_id)]:
            continue
        filtered.append(c)
    return filtered


def _phase_filter_candidates(candidates, phase):
    """Apply phase-based role/action filtering.

    phase=build   -> prioritize buyer/seller bootstrapping and dispute creation.
    phase=exploit -> prioritize resolver/governance/keeper lifecycle actions.
    """
    if not candidates:
        return candidates

    if phase == "build":
        keep = []
        for c in candidates:
            actor = c.get("actor_id")
            action = c.get("action")
            # Strict build gating: only buyer/seller should seed escrow/dispute flow.
            if actor in ("buyer", "seller") and action in (
                "create_escrow",
                "raise_dispute",
                "release",
                "sender_cancel",
                "recipient_cancel",
            ):
                keep.append(c)
        return keep or candidates

    if phase == "exploit":
        keep = []
        exploit_actions = {
            "execute_resolution",
            "propose_fraud_slash",
            "appeal_slash",
            "resolve_appeal",
            "execute_fraud_slash",
            "execute_pending_settlement",
        }
        for c in candidates:
            actor = c.get("actor_id")
            action = c.get("action")
            if actor in ("resolver", "governance", "keeper") or action in exploit_actions:
                keep.append(c)
        return keep or candidates

    return candidates


def _load_trace_events(trace_path):
    with open(trace_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    return data


def _normalise_event_params_for_grpc(params):
    p = dict(params or {})
    # gRPC server accepts either workflow-id or id depending on action handlers;
    # mirror id for compatibility when applicable.
    if "workflow-id" in p and "id" not in p:
        p["id"] = p["workflow-id"]
    if "workflow_id" in p and "id" not in p:
        p["id"] = p["workflow_id"]
    return p


def _replay_trace_prefix(step_session, session_id, trace_doc, prefix_steps=None):
    events = trace_doc.get("events", [])
    n = len(events) if prefix_steps is None else max(0, min(prefix_steps, len(events)))
    accepted = 0
    rejected = 0
    active_ids = []

    for i, ev in enumerate(events[:n]):
        params = _normalise_event_params_for_grpc(ev.get("params", {}))
        req = {
            "session_id": session_id,
            "event": {
                "seq": ev.get("seq", i),
                "time": ev.get("time", 1000 + i * 10),
                "agent": ev.get("agent"),
                "action": ev.get("action"),
                "params": params,
            },
        }
        resp = step_session(req)
        result = resp.get("result")
        if result == "ok":
            accepted += 1
            if ev.get("action") == "create_escrow":
                new_id = _extract_candidate_id(resp, active_ids)
                if new_id not in active_ids:
                    active_ids.append(new_id)
        else:
            rejected += 1

    return {
        "replayed_steps": n,
        "accepted": accepted,
        "rejected": rejected,
        "active_ids": active_ids,
    }


def _candidate_mutations_for_action(action, event):
    params = dict(event.get("params") or {})
    wf = params.get("workflow-id", params.get("workflow_id", params.get("id", "wf0")))
    if action == "execute_resolution":
        p1 = dict(params)
        p1["is-release"] = True
        p1["is_release"] = True
        p2 = dict(params)
        p2["is-release"] = False
        p2["is_release"] = False
        return [
            {"tag": "resolution-release", "action": "execute_resolution", "params": p1},
            {"tag": "resolution-refund", "action": "execute_resolution", "params": p2},
            {"tag": "slash-after-resolution", "action": "propose_fraud_slash", "params": {"workflow-id": wf, "resolver-addr": "0xresolver", "amount": 500}},
        ]
    if action == "execute_pending_settlement":
        return [
            {"tag": "keep-settlement", "action": "execute_pending_settlement", "params": params},
            {"tag": "slash-instead-of-settlement", "action": "propose_fraud_slash", "params": {"workflow-id": wf, "resolver-addr": "0xresolver", "amount": 500}},
        ]
    if action == "propose_fraud_slash":
        return [
            {"tag": "execute-slash", "action": "execute_fraud_slash", "params": {"workflow-id": wf}},
            {"tag": "resolve-appeal-deny", "action": "resolve_appeal", "params": {"workflow-id": wf, "upheld?": False}},
            {"tag": "resolve-appeal-uphold", "action": "resolve_appeal", "params": {"workflow-id": wf, "upheld?": True}},
        ]
    # eq-v9 / eq-v10 focused templates (adversarial-dispute traces)
    if action == "raise_dispute":
        return [
            {"tag": "resolver-self-dispute", "action": "raise_dispute", "params": {"workflow-id": wf}},
            {"tag": "force-release-after-dispute", "action": "execute_resolution", "params": {"workflow-id": wf, "is-release": True, "is_release": True, "resolution-hash": "0xhash"}},
            {"tag": "force-refund-after-dispute", "action": "execute_resolution", "params": {"workflow-id": wf, "is-release": False, "is_release": False, "resolution-hash": "0xhash"}},
        ]
    if action == "create_escrow":
        base_to = params.get("to", "0xseller")
        base_token = params.get("token", "USDC")
        return [
            {"tag": "micro-escrow", "action": "create_escrow", "params": {"token": base_token, "to": base_to, "amount": 1}},
            {"tag": "jumbo-escrow", "action": "create_escrow", "params": {"token": base_token, "to": base_to, "amount": 1000000}},
            {"tag": "self-recipient-escrow", "action": "create_escrow", "params": {"token": base_token, "to": "0xbuyer", "amount": params.get("amount", 4000)}},
        ]
    return []


def _build_trace_mutants(trace_doc, mutation_action, max_mutants=5):
    events = trace_doc.get("events", [])
    mutants = []
    for i, ev in enumerate(events):
        if ev.get("action") != mutation_action:
            continue
        for cand in _candidate_mutations_for_action(mutation_action, ev):
            m_events = [dict(x) for x in events]
            replaced = dict(m_events[i])
            replaced["action"] = cand["action"]
            replaced["params"] = cand.get("params", {})
            m_events[i] = replaced
            mutant_id = f"{mutation_action}@{i}:{cand.get('tag', cand['action'])}"
            mutants.append(
                {
                    "mutant_id": mutant_id,
                    "mutation_index": i,
                    "base_action": mutation_action,
                    "mutated_action": cand["action"],
                    "mutation_tag": cand.get("tag", cand["action"]),
                    "events": m_events,
                }
            )
            if len(mutants) >= max_mutants:
                return mutants
    return mutants


def run_adversarial_session(
    target_effective_steps=20,
    max_attempts=80,
    guided_ratio=0.75,
    rejected_step_weight=0.15,
    seed=None,
    objective_actor="resolver",
    objective_name="resolver_fraud_profit",
    attack_success_threshold=1.0,
    build_target_active_ids=2,
    seed_trace=None,
    prefix_steps=None,
    mutation_action=None,
    mutation_index=-1,
    max_mutants=0,
    mutation_id=None,
):
    if seed is not None:
        random.seed(seed)

    channel = grpc.insecure_channel('127.0.0.1:50051')

    # Define custom JSON codec for gRPC calls to bridge pure JSON server
    def json_serializer(data):
        return json.dumps(data).encode('utf-8')
    def json_deserializer(data):
        return json.loads(data.decode('utf-8'))

    # Build descriptors for manual RPC call (bypassing proto marshalling)
    start_session = channel.unary_unary(
        '/sew.simulation.SimulationEngine/StartSession',
        request_serializer=json_serializer,
        response_deserializer=json_deserializer
    )
    step_session = channel.unary_unary(
        '/sew.simulation.SimulationEngine/Step',
        request_serializer=json_serializer,
        response_deserializer=json_deserializer
    )
    destroy_session = channel.unary_unary(
        '/sew.simulation.SimulationEngine/DestroySession',
        request_serializer=json_serializer,
        response_deserializer=json_deserializer
    )
    suggest_actions = channel.unary_unary(
        '/sew.simulation.SimulationEngine/SuggestActions',
        request_serializer=json_serializer,
        response_deserializer=json_deserializer
    )
    session_signals = channel.unary_unary(
        '/sew.simulation.SimulationEngine/SessionSignals',
        request_serializer=json_serializer,
        response_deserializer=json_deserializer
    )
    evaluate_payoff = channel.unary_unary(
        '/sew.simulation.SimulationEngine/EvaluatePayoff',
        request_serializer=json_serializer,
        response_deserializer=json_deserializer
    )
    evaluate_objective = channel.unary_unary(
        '/sew.simulation.SimulationEngine/EvaluateAttackObjective',
        request_serializer=json_serializer,
        response_deserializer=json_deserializer
    )

    session_id = str(uuid.uuid4())
    
    # 1. Initialize with both buyer/seller so role-specific transitions are possible.
    trace_doc = _load_trace_events(seed_trace) if seed_trace else None
    if trace_doc and mutation_action and (mutation_index >= 0 or mutation_id):
        muts = _build_trace_mutants(trace_doc, mutation_action, max_mutants=max(1, max_mutants or 1))
        if muts:
            if mutation_id:
                chosen = next((m for m in muts if m["mutant_id"] == mutation_id), None)
                if chosen is None:
                    chosen = muts[0]
            else:
                chosen = muts[min(mutation_index, len(muts) - 1)]
            trace_doc = dict(trace_doc)
            trace_doc["events"] = chosen["events"]
            print("TRACE_MUTATION_APPLIED", {k: v for k, v in chosen.items() if k != "events"})
            print("TRACE_MUTATION_CATALOG", [{k: v for k, v in m.items() if k != "events"} for m in muts])
    trace_agents = trace_doc.get("agents") if trace_doc else None
    trace_params = trace_doc.get("protocol-params") if trace_doc else None

    start_payload = {
        "session_id": session_id,
        "agents": trace_agents or [
            {"id": "buyer", "address": "0xbuyer", "strategy": "honest"},
            {"id": "seller", "address": "0xseller", "strategy": "honest"},
            {"id": "resolver", "address": "0xresolver", "role": "resolver"},
            {"id": "governance", "address": "0xgovernance", "role": "governance"},
            {"id": "keeper", "address": "0xkeeper", "role": "keeper"},
        ],
    }
    if trace_params:
        start_payload["protocol_params"] = trace_params
    start_session(start_payload)

    active_ids = []
    rejection_counts = defaultdict(int)
    suppressed_until = defaultdict(int)
    attempts = 0
    ok_count = 0
    rejected_count = 0
    effective_steps = 0.0
    best_objective_score = None
    successful_attack = False
    successful_attack_step = None
    successful_attack_score = None
    trace_seed_meta = {
        "seed_trace": seed_trace,
        "prefix_steps": prefix_steps,
        "replayed_steps": 0,
        "replay_accepted": 0,
        "replay_rejected": 0,
    }
    
    try:
        if trace_doc:
            replay_info = _replay_trace_prefix(
                step_session,
                session_id,
                trace_doc,
                prefix_steps=prefix_steps,
            )
            active_ids = list(replay_info["active_ids"])
            trace_seed_meta.update(
                {
                    "replayed_steps": replay_info["replayed_steps"],
                    "replay_accepted": replay_info["accepted"],
                    "replay_rejected": replay_info["rejected"],
                }
            )
            print(
                "TRACE_SEED_REPLAY",
                {
                    "trace": seed_trace,
                    "replayed_steps": replay_info["replayed_steps"],
                    "accepted": replay_info["accepted"],
                    "rejected": replay_info["rejected"],
                    "active_ids": active_ids,
                },
            )

        # 2. Adversarial loop with effective-step budgeting
        seq = 0
        while attempts < max_attempts and effective_steps < target_effective_steps:
            print(f"Probing seq {seq}...")
            hints_buyer = suggest_actions({"session_id": session_id, "actor_id": "buyer"})
            hints_seller = suggest_actions({"session_id": session_id, "actor_id": "seller"})
            hints_resolver = suggest_actions({"session_id": session_id, "actor_id": "resolver"})
            hints_governance = suggest_actions({"session_id": session_id, "actor_id": "governance"})
            hints_keeper = suggest_actions({"session_id": session_id, "actor_id": "keeper"})

            suggested = []
            if hints_buyer.get("ok"):
                suggested.extend(hints_buyer.get("suggested_actions", []))
            if hints_seller.get("ok"):
                suggested.extend(hints_seller.get("suggested_actions", []))
            if hints_resolver.get("ok"):
                suggested.extend(hints_resolver.get("suggested_actions", []))
            if hints_governance.get("ok"):
                suggested.extend(hints_governance.get("suggested_actions", []))
            if hints_keeper.get("ok"):
                suggested.extend(hints_keeper.get("suggested_actions", []))

            suggested = _apply_suppression(suggested, suppressed_until, seq)
            phase = "build" if len(active_ids) < build_target_active_ids else "exploit"
            suggested = _phase_filter_candidates(suggested, phase)

            use_guided = bool(suggested) and (random.random() < guided_ratio)
            if use_guided:
                choice, pre_choice_score = _objective_guided_choice(
                    suggested,
                    rejection_counts,
                    active_ids,
                    evaluate_objective,
                    session_id,
                    objective_actor,
                    objective_name,
                )
                action = choice.get("action", _choose_action(active_ids))
                suggested_actor = choice.get("actor_id", "buyer")
                suggested_params = choice.get("params", {})
            else:
                # Chaos lane: deliberately explores beyond guided suggestions.
                action = _choose_action(active_ids)
                # Keep chaos lane phase-aware to reduce non-productive invalid spam.
                if phase == "build":
                    suggested_actor = random.choice(["buyer", "seller"])
                else:
                    suggested_actor = random.choice(["resolver", "governance", "keeper", "buyer", "seller"])
                suggested_params = {}

            # Actor selection: alternate for create; mostly buyer for adversarial probes.
            if action == "create_escrow":
                actor = suggested_actor
                params = dict(suggested_params) if suggested_params else {"token": "USDC", "to": "0xseller", "amount": 5000}
            else:
                actor = suggested_actor
                target_id = random.choice(active_ids) if active_ids else 0
                params = dict(suggested_params) if suggested_params else {"id": target_id}
                params["id"] = params.get("id", target_id)
            
            res = step_session({
                "session_id": session_id,
                "event": {
                    "seq": seq,
                    "time": 1000 + seq*10,
                    "agent": actor,
                    "action": action,
                    "params": params,
                },
            })

            result = res.get("result")
            attempts += 1

            if result == "ok" and action == "create_escrow":
                new_id = _extract_candidate_id(res, active_ids)
                if new_id not in active_ids:
                    active_ids.append(new_id)
            
            if res.get("halted"):
                print(f"!!! Invariant Violation Detected at seq {seq}!")
                break
            else:
                err = res.get("error")
                if result == "ok":
                    ok_count += 1
                    effective_steps += 1.0
                    print(f"  Step {seq} {actor} {action} result: ok (active_ids={active_ids})")
                else:
                    rejected_count += 1
                    effective_steps += rejected_step_weight
                    rejection_key = (actor, action, params.get("id"))
                    rejection_counts[rejection_key] += 1
                    if rejection_counts[rejection_key] >= 2:
                        # Short cooldown to avoid repeatedly hammering same failing tuple.
                        suppressed_until[rejection_key] = max(suppressed_until[rejection_key], seq + 3)
                    print(f"  Step {seq} {actor} {action} result: {result} error={err} (active_ids={active_ids})")

                signals = session_signals({"session_id": session_id})
                payoff = evaluate_payoff({"session_id": session_id, "actor_id": "buyer"})
                objective = evaluate_objective(
                    {
                        "session_id": session_id,
                        "actor_id": objective_actor,
                        "objective": objective_name,
                    }
                )
                if signals.get("ok") and payoff.get("ok"):
                    score = objective.get("score") if objective.get("ok") else None
                    if score is not None:
                        best_objective_score = score if best_objective_score is None else max(best_objective_score, score)
                        if (not successful_attack) and (score >= attack_success_threshold):
                            successful_attack = True
                            successful_attack_step = seq
                            successful_attack_score = score
                            print(
                                f"SUCCESSFUL_ATTACK objective={objective_name} actor={objective_actor} "
                                f"score={score} threshold={attack_success_threshold} step={seq}"
                            )
                    print(
                        "    signals:",
                        {
                            "block_time": signals.get("block_time"),
                            "pending_count": signals.get("pending_count"),
                            "active_workflow_ids": signals.get("active_workflow_ids"),
                        },
                        "payoff:",
                        {
                            "stake_locked": payoff.get("stake_locked"),
                            "slash_loss_realized": payoff.get("slash_loss_realized"),
                            "claimable": payoff.get("claimable"),
                            "bond_locked": payoff.get("bond_locked"),
                            "net_pnl": payoff.get("net_pnl"),
                        },
                        "objective:",
                        {
                            "name": objective.get("objective") if objective.get("ok") else None,
                            "score": score,
                            "decomposition": objective.get("decomposition") if objective.get("ok") else None,
                        },
                    )
            seq += 1

        print("\nRun summary:")
        summary = {
                "attempts": attempts,
                "ok_count": ok_count,
                "rejected_count": rejected_count,
                "effective_steps": round(effective_steps, 3),
                "target_effective_steps": target_effective_steps,
                "max_attempts": max_attempts,
                "guided_ratio": guided_ratio,
                "rejected_step_weight": rejected_step_weight,
                "active_ids": active_ids,
                "best_objective_score": best_objective_score,
                "objective_actor": objective_actor,
                "objective_name": objective_name,
                "successful_attack": successful_attack,
                "successful_attack_step": successful_attack_step,
                "successful_attack_score": successful_attack_score,
                "attack_success_threshold": attack_success_threshold,
                "build_target_active_ids": build_target_active_ids,
                "trace_seed": trace_seed_meta,
            }
        print(summary)
        return summary
            
    finally:
        destroy_session({"session_id": session_id})


def run_mutant_sweep(
    seed_trace,
    mutation_action,
    max_mutants,
    common_kwargs,
):
    trace_doc = _load_trace_events(seed_trace)
    mutants = _build_trace_mutants(trace_doc, mutation_action, max_mutants=max_mutants)
    if not mutants:
        print("MUTANT_SWEEP no mutants generated")
        return []

    print("MUTANT_SWEEP_CATALOG", [{k: v for k, v in m.items() if k != "events"} for m in mutants])
    results = []
    for m in mutants:
        print("MUTANT_SWEEP_RUN", m["mutant_id"])
        summary = run_adversarial_session(
            seed_trace=seed_trace,
            mutation_action=mutation_action,
            mutation_id=m["mutant_id"],
            **common_kwargs,
        )
        results.append(
            {
                "mutant_id": m["mutant_id"],
                "mutation_tag": m.get("mutation_tag"),
                "mutated_action": m.get("mutated_action"),
                "successful_attack": summary.get("successful_attack"),
                "best_objective_score": summary.get("best_objective_score"),
                "rejected_count": summary.get("rejected_count"),
                "attempts": summary.get("attempts"),
            }
        )

    ranked = sorted(
        results,
        key=lambda r: (
            0 if r["successful_attack"] else 1,
            -(r["best_objective_score"] or 0),
            r["rejected_count"] or 0,
        ),
    )
    print("MUTANT_SWEEP_RANKED", ranked)
    return ranked


def run_portfolio_sweep(
    traces,
    mutation_actions,
    max_mutants,
    common_kwargs,
):
    leaderboard = []
    for trace in traces:
        for action in mutation_actions:
            print("PORTFOLIO_SWEEP_START", {"trace": trace, "mutation_action": action})
            ranked = run_mutant_sweep(
                seed_trace=trace,
                mutation_action=action,
                max_mutants=max_mutants,
                common_kwargs=common_kwargs,
            )
            for row in ranked:
                leaderboard.append(
                    {
                        "trace": trace,
                        "mutation_action": action,
                        **row,
                    }
                )

    leaderboard = sorted(
        leaderboard,
        key=lambda r: (
            0 if r.get("successful_attack") else 1,
            -(r.get("best_objective_score") or 0),
            r.get("rejected_count") or 0,
        ),
    )
    print("PORTFOLIO_SWEEP_LEADERBOARD", leaderboard)
    return leaderboard


def run_mutation_coverage_preflight(traces, mutation_actions, max_mutants):
    report = []
    for trace in traces:
        trace_doc = _load_trace_events(trace)
        actions_present = [ev.get("action") for ev in trace_doc.get("events", [])]
        for action in mutation_actions:
            mutants = _build_trace_mutants(trace_doc, action, max_mutants=max_mutants)
            report.append(
                {
                    "trace": trace,
                    "mutation_action": action,
                    "events_with_action": sum(1 for a in actions_present if a == action),
                    "mutant_candidates": len(mutants),
                    "sample_mutant_ids": [m["mutant_id"] for m in mutants[:3]],
                }
            )
    print("MUTATION_COVERAGE_PREFLIGHT", report)
    return report

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Adversarial SEW gRPC probe runner")
    parser.add_argument("--target-effective-steps", type=float, default=20)
    parser.add_argument("--max-attempts", type=int, default=80)
    parser.add_argument("--guided-ratio", type=float, default=0.75)
    parser.add_argument("--rejected-step-weight", type=float, default=0.15)
    parser.add_argument("--seed", type=int, default=None)
    parser.add_argument("--objective-actor", type=str, default="resolver")
    parser.add_argument("--objective-name", type=str, default="resolver_fraud_profit")
    parser.add_argument("--attack-success-threshold", type=float, default=1.0)
    parser.add_argument("--build-target-active-ids", type=int, default=2)
    parser.add_argument("--seed-trace", type=str, default=None)
    parser.add_argument("--prefix-steps", type=str, default="all")
    parser.add_argument("--mutation-action", type=str, default=None)
    parser.add_argument("--mutation-index", type=int, default=-1)
    parser.add_argument("--max-mutants", type=int, default=5)
    parser.add_argument("--mutation-id", type=str, default=None)
    parser.add_argument("--sweep-mutants", action="store_true")
    parser.add_argument("--portfolio-traces", type=str, default=None,
                        help="Comma-separated trace paths for portfolio sweep")
    parser.add_argument("--portfolio-actions", type=str, default=None,
                        help="Comma-separated mutation actions for portfolio sweep")
    parser.add_argument("--coverage-preflight", action="store_true",
                        help="Only report mutation coverage for trace/action combinations")
    args = parser.parse_args()

    prefix_steps = None if args.prefix_steps == "all" else int(args.prefix_steps)

    common_kwargs = dict(
        target_effective_steps=args.target_effective_steps,
        max_attempts=args.max_attempts,
        guided_ratio=args.guided_ratio,
        rejected_step_weight=args.rejected_step_weight,
        seed=args.seed,
        objective_actor=args.objective_actor,
        objective_name=args.objective_name,
        attack_success_threshold=args.attack_success_threshold,
        build_target_active_ids=args.build_target_active_ids,
        prefix_steps=prefix_steps,
        mutation_index=args.mutation_index,
        max_mutants=args.max_mutants,
    )

    if args.coverage_preflight:
        if not args.portfolio_traces or not args.portfolio_actions:
            raise SystemExit("--coverage-preflight requires --portfolio-traces and --portfolio-actions")
        traces = [t.strip() for t in args.portfolio_traces.split(",") if t.strip()]
        actions = [a.strip() for a in args.portfolio_actions.split(",") if a.strip()]
        run_mutation_coverage_preflight(
            traces=traces,
            mutation_actions=actions,
            max_mutants=args.max_mutants,
        )
    elif args.portfolio_traces and args.portfolio_actions:
        traces = [t.strip() for t in args.portfolio_traces.split(",") if t.strip()]
        actions = [a.strip() for a in args.portfolio_actions.split(",") if a.strip()]
        run_portfolio_sweep(
            traces=traces,
            mutation_actions=actions,
            max_mutants=args.max_mutants,
            common_kwargs=common_kwargs,
        )
    elif args.sweep_mutants:
        if not args.seed_trace or not args.mutation_action:
            raise SystemExit("--sweep-mutants requires --seed-trace and --mutation-action")
        run_mutant_sweep(
            seed_trace=args.seed_trace,
            mutation_action=args.mutation_action,
            max_mutants=args.max_mutants,
            common_kwargs=common_kwargs,
        )
    else:
        run_adversarial_session(
            seed_trace=args.seed_trace,
            mutation_action=args.mutation_action,
            mutation_id=args.mutation_id,
            **common_kwargs,
        )
