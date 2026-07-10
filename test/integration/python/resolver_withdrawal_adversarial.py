"""
High-value adversarial scenario: resolver withdrawal pressure test.

Purpose:
  1) Probe whether a resolver can perform a voluntary stake-withdrawal action
     while holding an active dispute.
  2) Continue through a valid lifecycle to ensure no invariant/liveness break.

Notes:
  - The current action surface has stake registration/slashing but no explicit
    withdraw-stake action. We still probe by attempting a synthetic
    "withdraw_stake" action and assert it is rejected.
  - Requires the Clojure gRPC server on localhost:50051.

Run:
  cd integration/python && python resolver_withdrawal_adversarial.py
"""

from __future__ import annotations

import uuid
from typing import Any

from sim_api.grpc_client import SimulationClient, managed_session


AGENTS = [
    {"id": "buyer", "address": "0xbuyer", "strategy": "honest"},
    {"id": "seller", "address": "0xseller", "strategy": "honest"},
    {"id": "resolver", "address": "0xresolver", "role": "resolver"},
    {"id": "governance", "address": "0xgov", "role": "governance"},
]


def _print_step(event: dict[str, Any], resp: dict[str, Any]) -> None:
    entry = resp.get("trace_entry") or {}
    print(
        f"seq={event['seq']:02d} action={event['action']:<24} "
        f"result={resp.get('result')} error={entry.get('error')}"
    )


def main() -> int:
    print("\n=== Adversarial Scenario: Resolver Withdrawal Pressure ===")

    with SimulationClient(host="localhost", port=50051) as client:
        sid = f"adv-withdraw-{uuid.uuid4()}"
        with managed_session(
            client,
            AGENTS,
            protocol_params={
                "resolver_fee_bps": 50,
                "appeal_window_duration": 60,
                "max_dispute_duration": 3600,
            },
            initial_block_time=1000,
            session_id=sid,
        ):
            events = [
                # Resolver has stake and becomes active on a dispute.
                {"seq": 0, "time": 1000, "agent": "resolver", "action": "register_stake", "params": {"amount": 5000}},
                {"seq": 1, "time": 1000, "agent": "buyer", "action": "create_escrow", "params": {"token": "USDC", "to": "0xseller", "amount": 2000, "custom_resolver": "0xresolver"}},
                {"seq": 2, "time": 1060, "agent": "buyer", "action": "raise_dispute", "params": {"workflow_id": 0}},

                # Adversarial probe: withdrawal attempt while active dispute exists.
                {"seq": 3, "time": 1070, "agent": "resolver", "action": "withdraw_stake", "params": {"amount": 1000}},

                # Resolve dispute, finalize pending settlement, then retry withdraw.
                {"seq": 4, "time": 1120, "agent": "resolver", "action": "execute_resolution", "params": {"workflow_id": 0, "is_release": True}},
                {"seq": 5, "time": 1181, "agent": "buyer", "action": "execute_pending_settlement", "params": {"workflow_id": 0}},
                {"seq": 6, "time": 1190, "agent": "resolver", "action": "withdraw_stake", "params": {"amount": 1000}},
            ]

            rejected_withdraw = False
            accepted_withdraw_after_resolution = False
            halted = False
            for event in events:
                resp = client.step(sid, event)
                _print_step(event, resp)

                if event["action"] == "withdraw_stake" and resp.get("result") == "rejected":
                    rejected_withdraw = True
                if (
                    event["seq"] == 6
                    and event["action"] == "withdraw_stake"
                    and resp.get("result") == "ok"
                ):
                    accepted_withdraw_after_resolution = True

                if resp.get("halted"):
                    halted = True
                    break

            print("\n--- Summary ---")
            print(f"withdraw probe rejected: {rejected_withdraw}")
            print(f"post-resolution withdraw accepted: {accepted_withdraw_after_resolution}")
            print(f"halted: {halted}")

            if not rejected_withdraw:
                print("[FAIL] Expected synthetic withdraw_stake probe to be rejected")
                return 1
            if halted:
                print("[FAIL] Scenario halted unexpectedly")
                return 1
            if not accepted_withdraw_after_resolution:
                print("[FAIL] Expected post-resolution withdraw_stake to succeed")
                return 1

            print("[PASS] Withdraw blocked during dispute and allowed after resolution")
            return 0


if __name__ == "__main__":
    raise SystemExit(main())
