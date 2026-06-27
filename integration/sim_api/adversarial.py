"""
adversarial.py — Utilities for trace mutation and adversarial probing.
"""

import copy
import uuid
from sim_api.grpc_client import managed_session

class TracePerturber:
    """
    Utility to mutate event sequences for adversarial testing.
    """
    
    @staticmethod
    def insert_action(trace: list[dict], step_index: int, action: dict) -> list[dict]:
        """Insert an action at the specified step index."""
        new_trace = copy.deepcopy(trace)
        # Ensure sequential ordering for the injected action
        injected = copy.deepcopy(action)
        injected["seq"] = new_trace[step_index]["seq"]
        new_trace.insert(step_index, injected)
        return new_trace

    @staticmethod
    def replace_action(trace: list[dict], step_index: int, action: dict) -> list[dict]:
        """Replace the action at the specified step index."""
        new_trace = copy.deepcopy(trace)
        injected = copy.deepcopy(action)
        injected["seq"] = new_trace[step_index]["seq"]
        new_trace[step_index] = injected
        return new_trace

class AdversarialProber:
    """
    Orchestrator for running withdrawal-window fuzzing on a base trace.
    """
    def __init__(self, client, session_id):
        self.client = client
        self.session_id = session_id

    def probe_withdrawal_window(self, base_scenario: dict, workflow_id: str, actor_id: str):
        """
        Attempt to withdraw funds at every step of the provided base scenario.
        """
        results = []
        base_trace = base_scenario["events"]
        
        for i, step in enumerate(base_trace):
            # Create a withdrawal action
            withdrawal_action = {
                "agent": actor_id,
                "action": "withdraw_escrow",
                "params": {"workflow_id": workflow_id}
            }
            
            # Perturb the trace
            perturbed_events = TracePerturber.insert_action(base_trace, i, withdrawal_action)
            
            # Reset session and replay up to current perturbation
            print(f"Probing step {i} with withdraw_escrow...")
            try:
                with managed_session(self.client, base_scenario["agents"], base_scenario["protocol_params"], base_scenario["initial_block_time"]) as sid:
                    # Replay original trace up to i, then inject perturbation
                    for j, ev in enumerate(perturbed_events):
                        r = self.client.step(sid, ev)
                        if j == i:
                            results.append({"step": i, "result": r["result"], "error": r["trace_entry"].get("error")})
                            break
            except Exception as e:
                print(f"Probe error at step {i}: {e}")
            
        return results
