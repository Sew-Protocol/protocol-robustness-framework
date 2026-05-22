"""
smoke_test_readiness.py — Verify withdrawal actions, pause state, and full-state gRPC.
"""

import sys
import os
import uuid
import time

# Ensure we can import sew_sim
sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), ".")))

from sim_api.grpc_client import SimulationClient, managed_session

def test_readiness():
    print("Starting Readiness Smoke Test...")
    
    agents = [
        {"id": "buyer", "address": "0x111", "strategy": "honest"},
        {"id": "seller", "address": "0x222", "strategy": "honest"},
        {"id": "resolver", "address": "0x333", "role": "resolver"},
        {"id": "gov", "address": "0x000", "role": "governance"}
    ]
    
    with SimulationClient() as client:
        with managed_session(client, agents, session_id=f"smoke-{uuid.uuid4()}") as sid:
            
            # 1. Test Paused State
            print("Checking protocol pause...")
            r = client.step(sid, {"seq": 0, "time": 1000, "agent": "gov", "action": "set_paused", "params": {"paused?": True}})
            assert r["result"] == "ok"
            
            r = client.step(sid, {"seq": 1, "time": 1010, "agent": "buyer", "action": "create_escrow", 
                                  "params": {"token": "USDC", "to": "0x222", "amount": 1000}})
            assert r["result"] == "rejected"
            assert r["trace_entry"]["error"] == "protocol_paused"
            
            r = client.step(sid, {"seq": 2, "time": 1020, "agent": "gov", "action": "set_paused", "params": {"paused?": False}})
            assert r["result"] == "ok"
            
            r = client.step(sid, {"seq": 3, "time": 1030, "agent": "buyer", "action": "create_escrow", 
                                  "params": {"token": "USDC", "to": "0x222", "amount": 1000}})
            assert r["result"] == "ok"
            wf_id = r["trace_entry"]["extra"]["workflow_id"]
            
            # 2. Test Release and Withdrawal
            print("Checking release and withdrawal...")
            r = client.step(sid, {"seq": 4, "time": 1040, "agent": "buyer", "action": "release", "params": {"workflow_id": wf_id}})
            assert r["result"] == "ok"
            
            # The balance should be in :claimable now
            r = client.get_session_state(sid)
            assert r["ok"]
            world = r["world"]
            print(f"wf_id: {wf_id}")
            import pprint
            pprint.pprint(world)
            assert str(wf_id) in world["claimable"]
            assert world["claimable"][str(wf_id)]["0x222"] > 0
            
            # Withdraw it
            print("Withdrawing escrow...")
            r = client.step(sid, {"seq": 5, "time": 1050, "agent": "seller", "action": "withdraw_escrow", 
                                  "params": {"workflow_id": wf_id}})
            if r["result"] != "ok":
                print("Withdrawal failed trace entry:")
                pprint.pprint(r["trace_entry"])
            assert r["result"] == "ok"
            
            # Double withdrawal should fail
            r = client.step(sid, {"seq": 6, "time": 1060, "agent": "seller", "action": "withdraw_escrow", 
                                  "params": {"workflow_id": wf_id}})
            assert r["result"] == "rejected"
            assert r["trace_entry"]["error"] == "no_claimable_balance"
            
            # 3. Test Stake Exit Guard
            print("Checking resolver stake exit guard...")
            r = client.step(sid, {"seq": 7, "time": 1070, "agent": "resolver", "action": "register_stake", "params": {"amount": 5000}})
            assert r["result"] == "ok"
            
            # Create another escrow with this resolver
            r = client.step(sid, {"seq": 8, "time": 1080, "agent": "buyer", "action": "create_escrow", 
                                  "params": {"token": "USDC", "to": "0x222", "amount": 1000, "custom_resolver": "0x333"}})
            assert r["result"] == "ok"
            wf_id2 = r["trace_entry"]["extra"]["workflow_id"]
            
            # Raise dispute
            r = client.step(sid, {"seq": 9, "time": 1090, "agent": "buyer", "action": "raise_dispute", "params": {"workflow_id": wf_id2}})
            assert r["result"] == "ok"
            
            # Try to withdraw stake while dispute active
            r = client.step(sid, {"seq": 10, "time": 1100, "agent": "resolver", "action": "withdraw_stake", "params": {"amount": 1000}})
            assert r["result"] == "rejected"
            assert r["trace_entry"]["error"] == "active_disputes_block_withdrawal"
            
            print("All Readiness Tests PASSED!")

if __name__ == "__main__":
    test_readiness()
