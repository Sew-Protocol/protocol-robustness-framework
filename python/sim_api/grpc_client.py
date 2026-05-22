"""
Thin gRPC client for the generic Simulation Engine.

Uses grpcio's channel.unary_unary with custom JSON serializers — no protoc
compilation is required.  The Clojure server and this client agree on a
snake_case JSON wire format.

Authority model: Clojure is the sole source of truth.
  - This client sends events; it never computes state or enforces invariants.
  - World state is received per-step as a world_view snapshot.
"""

from __future__ import annotations

import json
import uuid
from contextlib import contextmanager
from typing import Any

import grpc


# ---------------------------------------------------------------------------
# Serializers
# ---------------------------------------------------------------------------

def _encode(obj: Any) -> bytes:
    return json.dumps(obj).encode("utf-8")


def _decode(data: bytes) -> Any:
    return json.loads(data.decode("utf-8"))


# ---------------------------------------------------------------------------
# Client
# ---------------------------------------------------------------------------

_ENGINE_SVC = "simulation.engine.SimulationEngine"
_ADVISORY_SVC = "simulation.engine.AdvisoryService"


class SimulationClient:
    """
    Thin wrapper around a gRPC channel for the generic Simulation Engine.

    All methods are synchronous unary calls.  For streaming adversarial use,
    call step() repeatedly from your own loop.

    Example::

        with SimulationClient() as client:
            r = client.start_session("s1", agents=[...], protocol_id="sew-v1")
            assert r["ok"]
            r = client.step("s1", {"seq": 0, "time": 1000, "agent": "buyer",
                                   "action": "create_escrow",
                                   "params": {"token": "USDC", "to": "0xabc",
                                              "amount": 1000}})
            assert r["result"] == "ok"
    """

    def __init__(self, host: str = "localhost", port: int = 7070, timeout: int = 30):
        self._channel = grpc.insecure_channel(f"{host}:{port}")
        self._timeout = timeout
        
        # Engine Service
        self.__start = self._channel.unary_unary(
            f"/{_ENGINE_SVC}/StartSession",
            request_serializer=_encode,
            response_deserializer=_decode,
        )
        self.__step = self._channel.unary_unary(
            f"/{_ENGINE_SVC}/Step",
            request_serializer=_encode,
            response_deserializer=_decode,
        )
        self.__get_state = self._channel.unary_unary(
            f"/{_ENGINE_SVC}/GetSessionState",
            request_serializer=_encode,
            response_deserializer=_decode,
        )
        self.__destroy = self._channel.unary_unary(
            f"/{_ENGINE_SVC}/DestroySession",
            request_serializer=_encode,
            response_deserializer=_decode,
        )
        
        # Advisory Service
        self.__suggest_actions = self._channel.unary_unary(
            f"/{_ADVISORY_SVC}/SuggestActions",
            request_serializer=_encode,
            response_deserializer=_decode,
        )
        self.__session_signals = self._channel.unary_unary(
            f"/{_ADVISORY_SVC}/SessionSignals",
            request_serializer=_encode,
            response_deserializer=_decode,
        )
        self.__evaluate_payoff = self._channel.unary_unary(
            f"/{_ADVISORY_SVC}/EvaluatePayoff",
            request_serializer=_encode,
            response_deserializer=_decode,
        )
        self.__evaluate_attack_objective = self._channel.unary_unary(
            f"/{_ADVISORY_SVC}/EvaluateAttackObjective",
            request_serializer=_encode,
            response_deserializer=_decode,
        )

    def _call(self, method: Any, request: Any, retries: int = 3) -> Any:
        """Internal helper with retries and standardized error reporting."""
        last_err = None
        for i in range(retries):
            try:
                return method(request, timeout=self._timeout)
            except grpc.RpcError as e:
                last_err = e
                # Only retry on transient errors
                if e.code() in (grpc.StatusCode.UNAVAILABLE, grpc.StatusCode.DEADLINE_EXCEEDED):
                    time.sleep(0.5 * (i + 1))
                    continue
                else:
                    raise RuntimeError(f"gRPC Error [{e.code()}]: {e.details()}")
        raise RuntimeError(f"gRPC Call failed after {retries} retries. Last error: {last_err}")

    # ------------------------------------------------------------------
    # Engine RPC methods
    # ------------------------------------------------------------------

    def start_session(
        self,
        session_id: str,
        agents: list[dict],
        protocol_params: dict | None = None,
        initial_block_time: int = 1000,
        protocol_id: str = "sew-v1",
    ) -> dict:
        """
        Allocate a new simulation session on the Clojure server.

        agents — list of dicts: [{"id": "buyer1", "address": "0x...", "role": "buyer", "strategy": "honest"}]
        Returns {"session_id": str, "ok": bool, "error": str|None}
        """
        return self._call(self.__start, {
            "session_id": session_id,
            "agents": agents,
            "protocol_params": protocol_params or {},
            "initial_block_time": initial_block_time,
            "protocol_id": protocol_id,
        })

    def step(self, session_id: str, event: dict) -> dict:
        """
        Execute one event against the session's canonical world state.

        event — dict:
          {"seq": int, "time": int, "agent": str, "action": str, "params": dict}

        Returns:
          {"session_id": str,
           "result": "ok"|"rejected"|"invariant_violated"|"error",
           "world_view": dict|None,   # lean world snapshot
           "trace_entry": dict|None,  # full step trace
           "halted": bool,
           "error": str|None}
        """
        return self._call(self.__step, {"session_id": session_id, "event": event})

    def get_session_state(self, session_id: str) -> dict:
        """
        Query the full internal world state of a session.
        Returns {"session_id": str, "ok": bool, "world": dict, "error": str|None}.
        """
        return self._call(self.__get_state, {"session_id": session_id})

    def destroy_session(self, session_id: str) -> dict:
        """Free session resources. Returns {"session_id": str, "ok": bool}."""
        return self._call(self.__destroy, {"session_id": session_id})

    # ------------------------------------------------------------------
    # Advisory RPC methods
    # ------------------------------------------------------------------

    def suggest_actions(self, session_id: str, actor_id: str) -> dict:
        """Return protocol-specific action suggestions for an actor."""
        return self._call(self.__suggest_actions, {"session_id": session_id, "actor_id": actor_id})

    def session_signals(self, session_id: str) -> dict:
        """Return protocol-specific risk/economic signals."""
        return self._call(self.__session_signals, {"session_id": session_id})

    def evaluate_payoff(self, session_id: str, actor_id: str) -> dict:
        """Return a realised payoff projection for an actor."""
        return self._call(self.__evaluate_payoff, {"session_id": session_id, "actor_id": actor_id})

    def evaluate_attack_objective(self, session_id: str, actor_id: str, objective: str | None = None) -> dict:
        """Evaluate an objective-oriented score for adversarial search."""
        return self._call(self.__evaluate_attack_objective, {
            "session_id": session_id, 
            "actor_id": actor_id, 
            "objective": objective
        })

    # ------------------------------------------------------------------
    # Context manager
    # ------------------------------------------------------------------

    def close(self) -> None:
        self._channel.close()

    def __enter__(self) -> "SimulationClient":
        return self

    def __exit__(self, *_args: Any) -> None:
        self.close()


# ---------------------------------------------------------------------------
# Convenience: session context manager
# ---------------------------------------------------------------------------

@contextmanager
def managed_session(
    client: SimulationClient,
    agents: list[dict],
    protocol_params: dict | None = None,
    initial_block_time: int = 1000,
    session_id: str | None = None,
    protocol_id: str = "sew-v1",
):
    """
    Context manager that creates a session on enter and destroys it on exit.

    Usage::

        with managed_session(client, agents, session_id="my-run") as sid:
            resp = client.step(sid, event)
    """
    sid = session_id or str(uuid.uuid4())
    resp = client.start_session(sid, agents, protocol_params, initial_block_time, protocol_id)
    if not resp.get("ok"):
        raise RuntimeError(f"StartSession failed: {resp.get('error')}")
    try:
        yield sid
    finally:
        client.destroy_session(sid)
