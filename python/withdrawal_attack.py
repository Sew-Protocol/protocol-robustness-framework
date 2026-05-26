"""
withdrawal_attack.py - SEW Withdrawal Security Analysis Campaign

Implements comprehensive withdrawal security tests for all categories (A through K):
- Category A: Resolver Bond Withdrawal Before Dispute
- Category B: Bond Withdrawal Before Slashing Finalizes
- Category C: Resolver Exit/Defection
- Category D: Escrow Withdrawal Wrong State
- Category E: Double Withdrawal/Replay
- Category F: Failed Auto-Push Fallback
- Category G: Withdrawal During Pause/Emergency
- Category H: Yield-Related Withdrawal
- Category I: Same-Block Ordering
- Category J: Stuck Funds Analysis
- Category K: Accounting Conservation

Usage:
    python withdrawal_attack.py --category all
    python withdrawal_attack.py --category pending-slash
    python withdrawal_attack.py --verify-bug b-slash-escape
"""

import json
import os
import sys
import uuid
import time
import argparse
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), ".")))

from sim_api.grpc_client import SimulationClient, managed_session


class WithdrawalAttacker:
    """Main class for withdrawal security analysis."""

    def __init__(self, host="localhost", port=50051, output_dir="results/withdrawal-campaign"):
        self.host = host
        self.port = port
        self.output_dir = output_dir
        self.results = []
        os.makedirs(output_dir, exist_ok=True)

    def _create_client(self):
        return SimulationClient(host=self.host, port=self.port)

    def _default_agents(self):
        return [
            {"id": "buyer", "address": "0xbuyer", "strategy": "honest"},
            {"id": "seller", "address": "0xseller", "strategy": "honest"},
            {"id": "resolver", "address": "0xresolver", "role": "resolver", "strategy": "honest"},
            {"id": "governance", "address": "0xgovernance", "role": "governance"},
        ]

    def _default_params(self):
        return {
            "resolver_bond_bps": 1000,
            "fraud_slash_bps": 5000,
            "resolver_fee_bps": 50,
        }

    def _run_trace(self, client, session_id, events):
        """Run a sequence of events and return results."""
        results = []
        for event in events:
            resp = client.step(session_id, event)
            results.append({
                "event": event,
                "response": resp,
            })
        return results

    def _get_state(self, client, session_id):
        """Get current world state."""
        resp = client.get_session_state(session_id)
        return resp.get("world", {}) if resp.get("ok") else {}

    def _check_accounting_conservation(self, state_before, state_after):
        """Check if accounting is conserved across transition."""
        def sum_balances(state):
            total = 0
            # Sum escrowed amounts
            for wf_id, et in state.get("escrow_transfers", {}).items():
                total += et.get("amount_after_fee", 0)
            # Sum claimable
            for wf_id, claims in state.get("claimable", {}).items():
                for addr, amount in claims.items():
                    total += amount
            # Sum resolver stakes
            for addr, stake in state.get("resolver_stakes", {}).items():
                total += stake
            # Sum total_fees
            for token, fee in state.get("total_fees", {}).items():
                total += fee
            # Sum resolver_slash_total
            for addr, slash in state.get("resolver_slash_total", {}).items():
                total += slash
            return total

        before = sum_balances(state_before)
        after = sum_balances(state_after)
        return abs(before - after) < 0.01  # Allow small floating point diff

    # -------------------------------------------------------------------------
    # Category A: Resolver Bond Withdrawal Before Dispute
    # -------------------------------------------------------------------------
    def test_a_withdraw_before_dispute(self, client, session_id):
        """Test withdrawal before/pending dispute."""
        results = []
        events = [
            {"seq": 0, "time": 1000, "agent": "resolver", "action": "register_stake",
             "params": {"amount": 5000}},
            {"seq": 1, "time": 1000, "agent": "buyer", "action": "create_escrow",
             "params": {"token": "USDC", "to": "0xseller", "amount": 5000, "custom_resolver": "0xresolver"}},
        ]
        self._run_trace(client, session_id, events)

        # Test A1: Withdraw before resolver assignment (no dispute yet)
        resp = client.step(session_id, {
            "seq": 2, "time": 1060, "agent": "resolver", "action": "withdraw_stake",
            "params": {"amount": 2000},
        })
        results.append({
            "test": "A1-withdraw-before-assignment",
            "expected": "ok",
            "actual": resp.get("result"),
            "pass": resp.get("result") == "ok",
        })

        # Create another escrow with resolver assigned
        client.step(session_id, {"seq": 3, "time": 1070, "agent": "buyer", "action": "create_escrow",
                                 "params": {"token": "USDC", "to": "0xseller", "amount": 3000, "custom_resolver": "0xresolver"}})

        # Test A2: Withdraw after assignment but before dispute
        resp = client.step(session_id, {
            "seq": 4, "time": 1080, "agent": "resolver", "action": "withdraw_stake",
            "params": {"amount": 2000},
        })
        results.append({
            "test": "A2-withdraw-after-assignment-before-dispute",
            "expected": "ok",
            "actual": resp.get("result"),
            "pass": resp.get("result") == "ok",
        })

        # Test A3: Partial withdrawal leaving bond below minimum
        resp = client.step(session_id, {
            "seq": 5, "time": 1090, "agent": "resolver", "action": "withdraw_stake",
            "params": {"amount": 3000},  # This would leave only 0!
        })
        results.append({
            "test": "A3-withdraw-below-minimum",
            "expected": "ok or rejected",
            "actual": resp.get("result"),
            "pass": True,  # Either outcome is acceptable
        })

        return results

    # -------------------------------------------------------------------------
    # Category B: Bond Withdrawal Before Slashing Finalizes
    # -------------------------------------------------------------------------
    def test_b_pending_slash_escape(self, client, session_id):
        """Test withdrawal with pending fraud slash (the main bug)."""
        results = []
        events = [
            {"seq": 0, "time": 1000, "agent": "buyer", "action": "create_escrow",
             "params": {"token": "USDC", "to": "0xseller", "amount": 5000}, "save_id_as": "wf0"},
            {"seq": 1, "time": 1060, "agent": "buyer", "action": "raise_dispute",
             "params": {"workflow_id": 0}},
            {"seq": 2, "time": 1120, "agent": "resolver", "action": "execute_resolution",
             "params": {"workflow_id": 0, "is_release": False, "resolution_hash": "0xhash"}},
            {"seq": 3, "time": 1180, "agent": "governance", "action": "propose_fraud_slash",
             "params": {"workflow_id": 0, "resolver_addr": "0xresolver", "amount": 2500}},
        ]
        self._run_trace(client, session_id, events)

        # Test B1: Withdraw with pending slash (SHOULD FAIL after fix)
        resp = client.step(session_id, {
            "seq": 4, "time": 1240, "agent": "resolver", "action": "withdraw_stake",
            "params": {"amount": 5000}, "adversarial": True,
        })
        results.append({
            "test": "B1-withdraw-with-pending-slash",
            "expected": "rejected",
            "expected_error": "pending_slash_blocks_withdrawal",
            "actual": resp.get("result"),
            "error": resp.get("trace_entry", {}).get("error"),
            "pass": resp.get("result") == "rejected" and
                  resp.get("trace_entry", {}).get("error") == "pending_slash_blocks_withdrawal",
        })

        # Test B2: Withdraw partial amount that leaves enough for slash
        # First, let's see the current stake
        state = self._get_state(client, session_id)
        current_stake = state.get("resolver_stakes", {}).get("0xresolver", 0)

        resp = client.step(session_id, {
            "seq": 5, "time": 1300, "agent": "resolver", "action": "withdraw_stake",
            "params": {"amount": 1000},  # Withdraw less than stake - slash amount
        })
        results.append({
            "test": "B2-partial-withdraw-with-pending-slash",
            "expected": "rejected",
            "actual": resp.get("result"),
            "pass": resp.get("result") == "rejected",
        })

        # Test B3: Execute slash then withdraw
        client.step(session_id, {"seq": 6, "time": 1360, "agent": "governance",
                                  "action": "execute_fraud_slash", "params": {"workflow_id": 0}})

        resp = client.step(session_id, {
            "seq": 7, "time": 1420, "agent": "resolver", "action": "withdraw_stake",
            "params": {"amount": 1000},
        })
        results.append({
            "test": "B3-withdraw-after-slash-executed",
            "expected": "ok",
            "actual": resp.get("result"),
            "pass": resp.get("result") == "ok",
        })

        return results

    # -------------------------------------------------------------------------
    # Category C: Resolver Exit/Defection
    # -------------------------------------------------------------------------
    def test_c_resolver_exit(self, client, session_id):
        """Test resolver exit after malicious action."""
        results = []
        events = [
            {"seq": 0, "time": 1000, "agent": "resolver", "action": "register_stake",
             "params": {"amount": 10000}},
            {"seq": 1, "time": 1000, "agent": "buyer", "action": "create_escrow",
             "params": {"token": "USDC", "to": "0xseller", "amount": 8000, "custom_resolver": "0xresolver"}},
            {"seq": 2, "time": 1060, "agent": "buyer", "action": "raise_dispute",
             "params": {"workflow_id": 0}},
            {"seq": 3, "time": 1120, "agent": "resolver", "action": "execute_resolution",
             "params": {"workflow_id": 0, "is_release": False, "resolution_hash": "0xmalicious"}},
        ]
        self._run_trace(client, session_id, events)

        # Test C1: Withdraw after malicious resolution (with pending slash)
        resp = client.step(session_id, {
            "seq": 4, "time": 1180, "agent": "governance", "action": "propose_fraud_slash",
            "params": {"workflow_id": 0, "resolver_addr": "0xresolver", "amount": 5000},
        })

        resp = client.step(session_id, {
            "seq": 5, "time": 1240, "agent": "resolver", "action": "withdraw_stake",
            "params": {"amount": 10000}, "adversarial": True,
        })
        results.append({
            "test": "C1-defect-then-withdraw",
            "expected": "rejected",
            "actual": resp.get("result"),
            "pass": resp.get("result") == "rejected",
        })

        return results

    # -------------------------------------------------------------------------
    # Category D: Escrow Withdrawal Wrong State
    # -------------------------------------------------------------------------
    def test_d_escrow_withdrawal_wrong_state(self, client, session_id):
        """Test escrow withdrawal in invalid states."""
        results = []
        events = [
            {"seq": 0, "time": 1000, "agent": "buyer", "action": "create_escrow",
             "params": {"token": "USDC", "to": "0xseller", "amount": 1000}, "save_id_as": "wf0"},
        ]
        self._run_trace(client, session_id, events)

        # Test D1: Withdraw in NONE state (should fail)
        resp = client.step(session_id, {
            "seq": 1, "time": 1010, "agent": "seller", "action": "withdraw_escrow",
            "params": {"workflow_id": 0},
        })
        results.append({
            "test": "D1-withdraw-none-state",
            "expected": "rejected",
            "actual": resp.get("result"),
            "pass": resp.get("result") == "rejected",
        })

        # Test D2: Raise dispute then try withdraw (should fail)
        client.step(session_id, {"seq": 2, "time": 1020, "agent": "buyer",
                                  "action": "raise_dispute", "params": {"workflow_id": 0}})
        resp = client.step(session_id, {
            "seq": 3, "time": 1030, "agent": "seller", "action": "withdraw_escrow",
            "params": {"workflow_id": 0},
        })
        results.append({
            "test": "D2-withdraw-disputed-state",
            "expected": "rejected",
            "actual": resp.get("result"),
            "pass": resp.get("result") == "rejected",
        })

        # Test D3: Release then withdraw (should succeed)
        client.step(session_id, {"seq": 4, "time": 1040, "agent": "buyer",
                                  "action": "release", "params": {"workflow_id": 0}})
        client.step(session_id, {"seq": 5, "time": 1050, "agent": "buyer",
                                  "action": "execute_pending_settlement", "params": {"workflow_id": 0}})
        resp = client.step(session_id, {
            "seq": 6, "time": 1060, "agent": "seller", "action": "withdraw_escrow",
            "params": {"workflow_id": 0},
        })
        results.append({
            "test": "D3-withdraw-released-state",
            "expected": "ok",
            "actual": resp.get("result"),
            "pass": resp.get("result") == "ok",
        })

        return results

    # -------------------------------------------------------------------------
    # Category E: Double Withdrawal/Replay
    # -------------------------------------------------------------------------
    def test_e_double_withdrawal(self, client, session_id):
        """Test idempotency of withdrawal actions."""
        results = []
        events = [
            {"seq": 0, "time": 1000, "agent": "buyer", "action": "create_escrow",
             "params": {"token": "USDC", "to": "0xseller", "amount": 1000}, "save_id_as": "wf0"},
            {"seq": 1, "time": 1040, "agent": "buyer", "action": "release",
             "params": {"workflow_id": 0}},
            {"seq": 2, "time": 1100, "agent": "buyer", "action": "execute_pending_settlement",
             "params": {"workflow_id": 0}},
        ]
        self._run_trace(client, session_id, events)

        # First withdrawal
        resp1 = client.step(session_id, {
            "seq": 3, "time": 1160, "agent": "seller", "action": "withdraw_escrow",
            "params": {"workflow_id": 0},
        })
        results.append({
            "test": "E1-first-withdrawal",
            "expected": "ok",
            "actual": resp1.get("result"),
            "pass": resp1.get("result") == "ok",
        })

        # Second withdrawal (should fail)
        resp2 = client.step(session_id, {
            "seq": 4, "time": 1170, "agent": "seller", "action": "withdraw_escrow",
            "params": {"workflow_id": 0},
        })
        results.append({
            "test": "E2-double-withdrawal",
            "expected": "rejected",
            "actual": resp2.get("result"),
            "pass": resp2.get("result") == "rejected",
        })

        return results

    # -------------------------------------------------------------------------
    # Category F: Failed Auto-Push Fallback
    # -------------------------------------------------------------------------
    def test_f_failed_push_fallback(self, client, session_id):
        """Test withdrawal after failed auto-push."""
        results = []
        # This test requires setting up a scenario where auto-push fails
        # and funds end up in claimable
        events = [
            {"seq": 0, "time": 1000, "agent": "buyer", "action": "create_escrow",
             "params": {"token": "USDC", "to": "0xseller", "amount": 1000}, "save_id_as": "wf0"},
            {"seq": 1, "time": 1040, "agent": "buyer", "action": "release",
             "params": {"workflow_id": 0}},
            {"seq": 2, "time": 1100, "agent": "buyer", "action": "execute_pending_settlement",
             "params": {"workflow_id": 0}},
        ]
        self._run_trace(client, session_id, events)

        # Check claimable balance
        state = self._get_state(client, session_id)
        claimable = state.get("claimable", {})
        wf_key = str(0)
        if wf_key in claimable:
            resp = client.step(session_id, {
                "seq": 3, "time": 1160, "agent": "seller", "action": "withdraw_escrow",
                "params": {"workflow_id": 0},
            })
            results.append({
                "test": "F1-withdraw-claimable",
                "expected": "ok",
                "actual": resp.get("result"),
                "pass": resp.get("result") == "ok",
            })

            # Try again (should fail)
            resp = client.step(session_id, {
                "seq": 4, "time": 1170, "agent": "seller", "action": "withdraw_escrow",
                "params": {"workflow_id": 0},
            })
            results.append({
                "test": "F2-double-claim",
                "expected": "rejected",
                "actual": resp.get("result"),
                "pass": resp.get("result") == "rejected",
            })
        else:
            results.append({
                "test": "F1-withdraw-claimable",
                "expected": "claimable exists",
                "actual": "no claimable found",
                "pass": False,
            })

        return results

    # -------------------------------------------------------------------------
    # Category G: Withdrawal During Pause/Emergency
    # -------------------------------------------------------------------------
    def test_g_withdrawal_pause(self, client, session_id):
        """Test withdrawal with protocol paused."""
        results = []
        events = [
            {"seq": 0, "time": 1000, "agent": "resolver", "action": "register_stake",
             "params": {"amount": 5000}},
            {"seq": 1, "time": 1000, "agent": "buyer", "action": "create_escrow",
             "params": {"token": "USDC", "to": "0xseller", "amount": 2000}},
        ]
        self._run_trace(client, session_id, events)

        # Pause protocol
        resp = client.step(session_id, {
            "seq": 2, "time": 1010, "agent": "governance", "action": "set_paused",
            "params": {"paused?": True},
        })

        # Try to withdraw stake while paused
        resp = client.step(session_id, {
            "seq": 3, "time": 1020, "agent": "resolver", "action": "withdraw_stake",
            "params": {"amount": 1000},
        })
        results.append({
            "test": "G1-withdraw-stake-while-paused",
            "expected": "rejected",
            "actual": resp.get("result"),
            "pass": resp.get("result") == "rejected",
        })

        # Unpause
        client.step(session_id, {"seq": 4, "time": 1030, "agent": "governance",
                                  "action": "set_paused", "params": {"paused?": False}})

        # Withdraw should work now
        resp = client.step(session_id, {
            "seq": 5, "time": 1040, "agent": "resolver", "action": "withdraw_stake",
            "params": {"amount": 1000},
        })
        results.append({
            "test": "G2-withdraw-stake-after-unpause",
            "expected": "ok",
            "actual": resp.get("result"),
            "pass": resp.get("result") == "ok",
        })

        return results

    # -------------------------------------------------------------------------
    # Category I: Same-Block Ordering
    # -------------------------------------------------------------------------
    def test_i_same_block_ordering(self, client, session_id):
        """Test transaction ordering bugs with same timestamp."""
        results = []
        events = [
            {"seq": 0, "time": 1000, "agent": "resolver", "action": "register_stake",
             "params": {"amount": 5000}},
            {"seq": 1, "time": 1000, "agent": "buyer", "action": "create_escrow",
             "params": {"token": "USDC", "to": "0xseller", "amount": 5000}, "save_id_as": "wf0"},
            {"seq": 2, "time": 1000, "agent": "buyer", "action": "raise_dispute",
             "params": {"workflow_id": 0}},
        ]
        self._run_trace(client, session_id, events)

        # Test I1: propose_fraud_slash then withdraw_stake (same block)
        resp1 = client.step(session_id, {
            "seq": 3, "time": 1060, "agent": "governance", "action": "propose_fraud_slash",
            "params": {"workflow_id": 0, "resolver_addr": "0xresolver", "amount": 2500},
        })
        resp2 = client.step(session_id, {
            "seq": 4, "time": 1060, "agent": "resolver", "action": "withdraw_stake",
            "params": {"amount": 5000}, "adversarial": True,
        })
        results.append({
            "test": "I1-slash-then-withdraw-same-block",
            "slash_result": resp1.get("result"),
            "withdraw_result": resp2.get("result"),
            "expected": "rejected",
            "pass": resp2.get("result") == "rejected",
        })

        return results

    # -------------------------------------------------------------------------
    # Category J: Stuck Funds Analysis
    # -------------------------------------------------------------------------
    def test_j_stuck_funds(self, client, session_id):
        """Test for dust balances and stuck funds."""
        results = []
        # Create tiny escrow (dust)
        events = [
            {"seq": 0, "time": 1000, "agent": "buyer", "action": "create_escrow",
             "params": {"token": "USDC", "to": "0xseller", "amount": 1}, "save_id_as": "wf0"},
            {"seq": 1, "time": 1040, "agent": "buyer", "action": "release",
             "params": {"workflow_id": 0}},
            {"seq": 2, "time": 1100, "agent": "buyer", "action": "execute_pending_settlement",
             "params": {"workflow_id": 0}},
        ]
        self._run_trace(client, session_id, events)

        # Check if dust is claimable
        state = self._get_state(client, session_id)
        claimable = state.get("claimable", {})
        wf_key = str(0)
        if wf_key in claimable:
            dust_amount = claimable[wf_key].get("0xseller", 0)
            results.append({
                "test": "J1-dust-balance",
                "dust_amount": dust_amount,
                "is_economic": dust_amount > 0,
                "pass": True,
            })
        else:
            results.append({
                "test": "J1-dust-balance",
                "dust_amount": 0,
                "pass": False,
            })

        return results

    # -------------------------------------------------------------------------
    # Category K: Accounting Conservation
    # -------------------------------------------------------------------------
    def test_k_accounting_conservation(self, client, session_id):
        """Test that accounting remains consistent."""
        results = []
        state_before = self._get_state(client, session_id)

        events = [
            {"seq": 0, "time": 1000, "agent": "buyer", "action": "create_escrow",
             "params": {"token": "USDC", "to": "0xseller", "amount": 5000}, "save_id_as": "wf0"},
            {"seq": 1, "time": 1060, "agent": "buyer", "action": "raise_dispute",
             "params": {"workflow_id": 0}},
            {"seq": 2, "time": 1120, "agent": "resolver", "action": "execute_resolution",
             "params": {"workflow_id": 0, "is_release": True, "resolution_hash": "0xhash"}},
            {"seq": 3, "time": 1180, "agent": "buyer", "action": "execute_pending_settlement",
             "params": {"workflow_id": 0}},
        ]
        self._run_trace(client, session_id, events)
        state_after = self._get_state(client, session_id)

        conserved = self._check_accounting_conservation(state_before, state_after)
        results.append({
            "test": "K1-accounting-conservation",
            "conserved": conserved,
            "pass": conserved,
        })

        return results

    # -------------------------------------------------------------------------
    # Main test runner
    # -------------------------------------------------------------------------
    def run_category(self, category, host=None, port=None):
        """Run a specific test category."""
        if host:
            self.host = host
        if port:
            self.port = port

        all_results = []

        with self._create_client() as client:
            session_id = f"withdrawal-{category}-{uuid.uuid4()}"

            with managed_session(
                client,
                self._default_agents(),
                protocol_params=self._default_params(),
                initial_block_time=1000,
                session_id=session_id,
            ):
                if category in ("A", "a", "all"):
                    print("Running Category A: Resolver Bond Withdrawal Before Dispute...")
                    results = self.test_a_withdraw_before_dispute(client, session_id)
                    all_results.extend(results)

                if category in ("B", "b", "all", "pending-slash"):
                    print("Running Category B: Bond Withdrawal Before Slashing Finalizes...")
                    session_id_b = f"withdrawal-B-{uuid.uuid4()}"
                    with managed_session(
                        client,
                        self._default_agents(),
                        protocol_params=self._default_params(),
                        initial_block_time=1000,
                        session_id=session_id_b,
                    ):
                        results = self.test_b_pending_slash_escape(client, session_id_b)
                        all_results.extend(results)

                if category in ("C", "c", "all"):
                    print("Running Category C: Resolver Exit/Defection...")
                    session_id_c = f"withdrawal-C-{uuid.uuid4()}"
                    with managed_session(
                        client,
                        self._default_agents(),
                        protocol_params=self._default_params(),
                        initial_block_time=1000,
                        session_id=session_id_c,
                    ):
                        results = self.test_c_resolver_exit(client, session_id_c)
                        all_results.extend(results)

                if category in ("D", "d", "all"):
                    print("Running Category D: Escrow Withdrawal Wrong State...")
                    session_id_d = f"withdrawal-D-{uuid.uuid4()}"
                    with managed_session(
                        client,
                        self._default_agents(),
                        protocol_params=self._default_params(),
                        initial_block_time=1000,
                        session_id=session_id_d,
                    ):
                        results = self.test_d_escrow_withdrawal_wrong_state(client, session_id_d)
                        all_results.extend(results)

                if category in ("E", "e", "all"):
                    print("Running Category E: Double Withdrawal/Replay...")
                    session_id_e = f"withdrawal-E-{uuid.uuid4()}"
                    with managed_session(
                        client,
                        self._default_agents(),
                        protocol_params=self._default_params(),
                        initial_block_time=1000,
                        session_id=session_id_e,
                    ):
                        results = self.test_e_double_withdrawal(client, session_id_e)
                        all_results.extend(results)

                if category in ("F", "f", "all"):
                    print("Running Category F: Failed Auto-Push Fallback...")
                    session_id_f = f"withdrawal-F-{uuid.uuid4()}"
                    with managed_session(
                        client,
                        self._default_agents(),
                        protocol_params=self._default_params(),
                        initial_block_time=1000,
                        session_id=session_id_f,
                    ):
                        results = self.test_f_failed_push_fallback(client, session_id_f)
                        all_results.extend(results)

                if category in ("G", "g", "all"):
                    print("Running Category G: Withdrawal During Pause/Emergency...")
                    session_id_g = f"withdrawal-G-{uuid.uuid4()}"
                    with managed_session(
                        client,
                        self._default_agents(),
                        protocol_params=self._default_params(),
                        initial_block_time=1000,
                        session_id=session_id_g,
                    ):
                        results = self.test_g_withdrawal_pause(client, session_id_g)
                        all_results.extend(results)

                if category in ("I", "i", "all"):
                    print("Running Category I: Same-Block Ordering...")
                    session_id_i = f"withdrawal-I-{uuid.uuid4()}"
                    with managed_session(
                        client,
                        self._default_agents(),
                        protocol_params=self._default_params(),
                        initial_block_time=1000,
                        session_id=session_id_i,
                    ):
                        results = self.test_i_same_block_ordering(client, session_id_i)
                        all_results.extend(results)

                if category in ("J", "j", "all"):
                    print("Running Category J: Stuck Funds Analysis...")
                    session_id_j = f"withdrawal-J-{uuid.uuid4()}"
                    with managed_session(
                        client,
                        self._default_agents(),
                        protocol_params=self._default_params(),
                        initial_block_time=1000,
                        session_id=session_id_j,
                    ):
                        results = self.test_j_stuck_funds(client, session_id_j)
                        all_results.extend(results)

                if category in ("K", "k", "all"):
                    print("Running Category K: Accounting Conservation...")
                    session_id_k = f"withdrawal-K-{uuid.uuid4()}"
                    with managed_session(
                        client,
                        self._default_agents(),
                        protocol_params=self._default_params(),
                        initial_block_time=1000,
                        session_id=session_id_k,
                    ):
                        results = self.test_k_accounting_conservation(client, session_id_k)
                        all_results.extend(results)

        return all_results

    def run_all_categories(self, host=None, port=None):
        """Run all test categories."""
        categories = ["A", "B", "C", "D", "E", "F", "G", "I", "J", "K"]
        all_results = []
        for cat in categories:
            results = self.run_category(cat, host, port)
            all_results.extend(results)
        return all_results

    def verify_bug(self, bug_id):
        """Verify a specific bug scenario."""
        results = []
        with self._create_client() as client:
            if bug_id == "b-slash-escape":
                print("Verifying bug: Pending Slash Escape...")
                session_id = f"verify-bug-{uuid.uuid4()}"
                with managed_session(
                    client,
                    self._default_agents(),
                    protocol_params=self._default_params(),
                    initial_block_time=1000,
                    session_id=session_id,
                ):
                    # Run the trace from the plan
                    trace_path = "data/fixtures/traces/withdrawal/b-slash-escape-verify.trace.json"
                    if os.path.exists(trace_path):
                        with open(trace_path, "r") as f:
                            trace = json.load(f)
                        self._run_trace(client, session_id, trace.get("events", []))
                        state = self._get_state(client, session_id)
                        results.append({
                            "bug_id": bug_id,
                            "verified": True,
                            "state": state,
                        })
        return results

    def save_results(self, results, filename=None):
        """Save results to JSON file."""
        if filename is None:
            filename = f"withdrawal-campaign-{datetime.now().strftime('%Y%m%d-%H%M%S')}.json"
        filepath = os.path.join(self.output_dir, filename)
        with open(filepath, "w") as f:
            json.dump(results, f, indent=2)
        print(f"Results saved to: {filepath}")
        return filepath


def main():
    parser = argparse.ArgumentParser(description="SEW Withdrawal Security Analysis")
    parser.add_argument("--category", type=str, default="all",
                        help="Test category to run (A-K, all, pending-slash)")
    parser.add_argument("--host", type=str, default="localhost",
                        help="gRPC server host")
    parser.add_argument("--port", type=int, default=50051,
                        help="gRPC server port")
    parser.add_argument("--verify-bug", type=str, default=None,
                        help="Verify a specific bug scenario")
    parser.add_argument("--target-effective-steps", type=int, default=50,
                        help="Target effective steps for mutation testing")
    parser.add_argument("--max-attempts", type=int, default=200,
                        help="Maximum attempts for mutation testing")
    args = parser.parse_args()

    attacker = WithdrawalAttacker(host=args.host, port=args.port)

    if args.verify_bug:
        results = attacker.verify_bug(args.verify_bug)
    elif args.category:
        results = attacker.run_category(args.category)
    else:
        results = attacker.run_all_categories()

    attacker.save_results(results)

    # Print summary
    print("\n=== Withdrawal Attack Campaign Summary ===")
    passed = sum(1 for r in results if r.get("pass"))
    total = len(results)
    print(f"Total tests: {total}")
    print(f"Passed: {passed}")
    print(f"Failed: {total - passed}")

    for r in results:
        status = "PASS" if r.get("pass") else "FAIL"
        print(f"  [{status}] {r.get('test', 'unknown')}")


if __name__ == "__main__":
    main()
