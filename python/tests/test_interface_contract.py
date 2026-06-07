"""Interface contract tests for Python ↔ Clojure replay bridge."""

from __future__ import annotations

from sim_api.event_params import normalise_event_params_for_grpc


def test_workflow_id_aliases_map_to_id() -> None:
    assert normalise_event_params_for_grpc({"workflow_id": 0}) == {
        "workflow_id": 0,
        "id": 0,
    }
    assert normalise_event_params_for_grpc({"workflow-id": 2}) == {
        "workflow-id": 2,
        "id": 2,
    }


def test_event_id_and_hop_id_mirrored() -> None:
    snake = normalise_event_params_for_grpc(
        {"workflow_id": 0, "event_id": "evt-1", "hop_id": 1}
    )
    assert snake["event-id"] == "evt-1"
    assert snake["event_id"] == "evt-1"
    assert snake["hop-id"] == 1
    assert snake["hop_id"] == 1

    kebab = normalise_event_params_for_grpc(
        {"workflow-id": 0, "event-id": "evt-2", "hop-id": 2}
    )
    assert kebab["event_id"] == "evt-2"
    assert kebab["event-id"] == "evt-2"
    assert kebab["hop_id"] == 2
    assert kebab["hop-id"] == 2


def test_idempotence_keys_preserved_without_workflow_id() -> None:
    params = normalise_event_params_for_grpc({"event-id": "evt-only"})
    assert params == {"event-id": "evt-only", "event_id": "evt-only"}
