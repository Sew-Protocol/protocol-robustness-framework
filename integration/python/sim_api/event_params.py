"""Wire-format normalization for gRPC replay event params."""

from __future__ import annotations


def _mirror_key(params: dict, kebab: str, snake: str) -> None:
    if snake in params and kebab not in params:
        params[kebab] = params[snake]
    if kebab in params and snake not in params:
        params[snake] = params[kebab]


def normalise_event_params_for_grpc(params: dict | None) -> dict:
    """Normalize scenario event params for the Clojure gRPC step API.

    Mirrors legacy workflow id aliases and replay idempotence keys so callers
    may use either kebab-case (JSON scenarios) or snake_case (Python payloads).
    """
    p = dict(params or {})

    if "workflow-id" in p and "id" not in p:
        p["id"] = p["workflow-id"]
    if "workflow_id" in p and "id" not in p:
        p["id"] = p["workflow_id"]

    _mirror_key(p, "event-id", "event_id")
    _mirror_key(p, "hop-id", "hop_id")

    return p
