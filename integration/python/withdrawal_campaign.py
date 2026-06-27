"""
withdrawal_campaign.py - Withdrawal-Focused Trace-Intervention Campaign
"""

import json
import os
import sys
import uuid
import time

sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), ".")))

from sim_api.grpc_client import SimulationClient, managed_session


class WithdrawalCampaign:
    def __init__(self, host="localhost", port=50051, output_dir="results/withdrawal-campaign"):
        self.host = host
        self.port = port
        self.output_dir = output_dir
        self.results = []
        os.makedirs(output_dir, exist_ok=True)
        
    def _create_client(self):
        return SimulationClient(host=self.host, port=self.port)
    
    def _get_state(self, client, session_id):
        resp = client.get_session_state(session_id)
        return resp.get("world", {}) if resp.get("ok") else {}
    
    def _check_accounting(self, state_before, state_after):
        """Check if accounting is conserved."""
        def sum_balances(state):
            total = 0
            # Escrowed amounts
            for wf_id, et in state.get("escrow_transfers", {}).items():
                total += et.get("amount_after_fee", 0)
            # Claimable
            for wf_id, claims in state.get("claimable", {}).items():
                for addr, amount in claims.items():
                    total += amount
            # Resolver stakes
            for addr, stake in state.get("resolver_stakes", {}).items():
                total += stake
            # Total fees
            for token, fee in state.get("total_fees", {}).items():
                total += fee
            # Slashed total
            for addr, slashed in state.get("resolver_slash_total", {}).items():
                total += slashed
            return total
        
        before = sum_balances(state_before)
        after = sum_balances(state_after)
        return abs(before - after) < 0.01
    
    def _attempt_withdrawal(self, client, session_id, actor, action, params):
        event = {
            "seq": 9999,
            "time": int(time.time()),
            "agent": actor,
            "action": action,
            "params": params
        }
        resp = client.step(session_id, event)
        return {
            "actor": actor,
            "action": action,
            "params": params,
            "result": resp.get("result"),
            "error": resp.get("trace_entry", {}).get("error"),
            "halted": resp.get("halted", False)
        }
    
    # =================================================================
    # Category B: Bond withdrawal before slashing finalizes
    # =================================================================
    def test_category_b(self, client, session_id):
        """Test withdrawal when pending slashes exist."""
        results = []
        
        # Setup: register stake
        client.step(session_id, {"seq": 0, "time": 1000, "agent": "resolver", "action": "register_stake",
             "params": {"amount": 10000}})
        
        # Create escrow
        r = client.step(session_id, {"seq": 1, "time": 1000, "agent": "buyer", "action": "create_escrow",
                                        "params": {"token": "USDC", "to": "0xseller", "amount": 5000}})
        wf_id = r['trace_entry']['extra']['workflow_id']
        print(f"  Created escrow: wf_id={wf_id}")
        
        # Raise dispute
        r = client.step(session_id, {"seq": 2, "time": 1060, "agent": "buyer", "action": "raise_dispute",
                                        "params": {"workflow_id": str(wf_id)}})
        print(f"  raise_dispute: {r['result']}")
        
        # Execute resolution (make resolver malicious)
        r = client.step(session_id, {"seq": 3, "time": 1120, "agent": "resolver", "action": "execute_resolution",
                                        "params": {"workflow_id": str(wf_id), "is_release": False, "resolution_hash": "0xhash"}})
        print(f"  execute_resolution: {r['result']}")
        
        # Propose fraud slash (creates pending slash)
        r = client.step(session_id, {"seq": 4, "time": 1180, "agent": "governance", "action": "propose_fraud_slash",
                                        "params": {"workflow_id": str(wf_id), "resolver_addr": "0xresolver", "amount": 2500}})
        print(f"  propose_fraud_slash: {r['result']}")
        
        # Test B1: Withdraw with pending slash (should FAIL with our fix)
        state_before = self._get_state(client, session_id)
        r = self._attempt_withdrawal(client, session_id, "resolver", 
                                      "withdraw_stake", {"amount": 5000})
        state_after = self._get_state(client, session_id)
        results.append({
            "category": "B1", "test": "withdraw_with_pending_slash",
            "expected": "rejected", "actual": r["result"],
            "expected_error": "pending_slash_blocks_withdrawal",
            "actual_error": r["error"],
            "pass": r["result"] == "rejected" and r["error"] == "pending_slash_blocks_withdrawal",
            "accounting_ok": self._check_accounting(state_before, state_after)
        })
        
        # Test B2: Withdraw partial (should also fail)
        r = self._attempt_withdrawal(client, session_id, "resolver", 
                                      "withdraw_stake", {"amount": 1000})
        results.append({
            "category": "B2", "test": "partial_withdraw_with_pending_slash",
            "expected": "rejected", "actual": r["result"],
            "pass": r["result"] == "rejected",
        })
        
        # Test B3: Execute slash then withdraw (should succeed)
        r = client.step(session_id, {"seq": 5, "time": 1240, "agent": "governance",
                                    "action": "execute_fraud_slash", 
                                    "params": {"workflow_id": wf_id}})
        print(f"  execute_fraud_slash: {r['result']}")
        
        r = self._attempt_withdrawal(client, session_id, "resolver", 
                                      "withdraw_stake", {"amount": 1000})
        results.append({
            "category": "B3", "test": "withdraw_after_slash_executed",
            "expected": "ok", "actual": r["result"],
            "pass": r["result"] == "ok",
        })
        
        return results
    
    # =================================================================
    # Category D: Escrow withdrawal wrong state
    # =================================================================
    def test_category_d(self, client, session_id):
        """Test escrow withdrawal in every lifecycle state."""
        results = []
        
        # Create escrow and get wf_id
        r = client.step(session_id, {"seq": 0, "time": 1000, "agent": "buyer", "action": "create_escrow",
                                          "params": {"token": "USDC", "to": "0xseller", "amount": 1000}})
        wf_id = r['trace_entry']['extra']['workflow_id']
        print(f"  Created escrow: wf_id={wf_id}")
        
        # Test D1: Withdraw in NONE state (should fail)
        r = self._attempt_withdrawal(client, session_id, "seller", 
                                      "withdraw_escrow", {"workflow_id": str(wf_id)})
        results.append({
            "category": "D1", "test": "withdraw_none_state",
            "expected": "rejected", "actual": r["result"],
            "pass": r["result"] == "rejected",
        })
        
        # Test D2: Withdraw in PENDING state (should fail)
        r = self._attempt_withdrawal(client, session_id, "seller", 
                                      "withdraw_escrow", {"workflow_id": str(wf_id)})
        results.append({
            "category": "D2", "test": "withdraw_pending_state",
            "expected": "rejected", "actual": r["result"],
            "pass": r["result"] == "rejected",
        })
        
        # Test D3: Create dispute then try withdraw (should fail)
        client.step(session_id, {"seq": 1, "time": 1060, "agent": "buyer",
                                    "action": "raise_dispute", 
                                    "params": {"workflow_id": str(wf_id)}})
        
        r = self._attempt_withdrawal(client, session_id, "seller", 
                                      "withdraw_escrow", {"workflow_id": str(wf_id)})
        results.append({
            "category": "D3", "test": "withdraw_disputed_state",
            "expected": "rejected", "actual": r["result"],
            "pass": r["result"] == "rejected",
        })
        
        return results
    
    # =================================================================
    # Category E: Double withdrawal / replay
    # =================================================================
    def test_category_e(self, client, session_id):
        """Test idempotency of withdrawal actions."""
        results = []
        
        # Create a released escrow and get wf_id
        r = client.step(session_id, {"seq": 0, "time": 1000, "agent": "buyer", "action": "create_escrow",
                                          "params": {"token": "USDC", "to": "0xseller", "amount": 1000}})
        wf_id = r['trace_entry']['extra']['workflow_id']
        print(f"  Created escrow: wf_id={wf_id}")
        
        client.step(session_id, {"seq": 1, "time": 1060, "agent": "buyer", "action": "release",
                                    "params": {"workflow_id": str(wf_id)}})
        client.step(session_id, {"seq": 2, "time": 1120, "agent": "buyer", "action": "execute_pending_settlement",
                                    "params": {"workflow_id": str(wf_id)}})
        
        # First withdrawal (should succeed)
        r1 = self._attempt_withdrawal(client, session_id, "seller", 
                                         "withdraw_escrow", {"workflow_id": str(wf_id)})
        results.append({
            "category": "E1", "test": "first_withdrawal",
            "expected": "ok", "actual": r1["result"],
            "pass": r1["result"] == "ok"
        })
        
        # Second withdrawal (should fail - idempotency)
        r2 = self._attempt_withdrawal(client, session_id, "seller", 
                                         "withdraw_escrow", {"workflow_id": str(wf_id)})
        results.append({
            "category": "E2", "test": "double_withdrawal",
            "expected": "rejected", "actual": r2["result"],
            "pass": r2["result"] == "rejected"
        })
        
        return results
    
    # =================================================================
    # Main campaign runner
    # =================================================================
    def run_campaign(self, categories="all"):
        """Run the full withdrawal campaign."""
        all_results = []
        categories_to_run = ["B", "D", "E"] if categories == "all" else [categories]
        
        with self._create_client() as client:
            for cat in categories_to_run:
                print(f"\n{'='*60}")
                print(f"Running Category {cat}")
                print('='*60)
                
                session_id = f"campaign-{cat}-{uuid.uuid4()}"
                agents = [
                    {"id": "buyer", "address": "0xbuyer", "strategy": "honest"},
                    {"id": "seller", "address": "0xseller", "strategy": "honest"},
                    {"id": "resolver", "address": "0xresolver", "role": "resolver", "strategy": "honest"},
                    {"id": "governance", "address": "0xgovernance", "role": "governance"},
                ]
                
                with managed_session(client, agents, session_id=session_id, 
                                     initial_block_time=1000):
                    if cat == "B":
                        results = self.test_category_b(client, session_id)
                    elif cat == "D":
                        results = self.test_category_d(client, session_id)
                    elif cat == "E":
                        results = self.test_category_e(client, session_id)
                    else:
                        results = []
                    
                    all_results.extend(results)
                    print(f"Category {cat}: {len(results)} tests completed")
                    passed = sum(1 for r in results if r.get("pass"))
                    print(f"  Passed: {passed}/{len(results)}")
        
        return all_results
    
    def save_results(self, results, filename=None):
        """Save results to JSON file."""
        if filename is None:
            filename = f"withdrawal_campaign_{int(time.time())}.json"
        filepath = os.path.join(self.output_dir, filename)
        with open(filepath, "w") as f:
            json.dump(results, f, indent=2)
        print(f"\nResults saved to: {filepath}")
        return filepath


def main():
    import argparse
    parser = argparse.ArgumentParser(description="Withdrawal Campaign")
    parser.add_argument("--categories", type=str, default="all",
                        help="Categories to run (B, D, E, or all)")
    parser.add_argument("--port", type=int, default=50051,
                        help="gRPC server port")
    args = parser.parse_args()
    
    campaign = WithdrawalCampaign(port=args.port)
    results = campaign.run_campaign(categories=args.categories)
    campaign.save_results(results)
    
    # Print summary
    print(f"\n{'='*60}")
    print("CAMPAIGN SUMMARY")
    print('='*60)
    total = len(results)
    passed = sum(1 for r in results if r.get("pass"))
    print(f"Total tests: {total}")
    print(f"Passed: {passed}")
    print(f"Failed: {total - passed}")
    
    # Show failures
    failures = [r for r in results if not r.get("pass")]
    if failures:
        print("\nFailures:")
        for f in failures:
            print(f"  [{f['category']}] {f['test']}: expected={f.get('expected')}, actual={f.get('actual')}")


if __name__ == "__main__":
    main()
