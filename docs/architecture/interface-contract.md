# Interface Contract (Clojure Core + Python Adversarial Bridge)

## Purpose

This document defines the interface boundary between:

1. **Core simulation authority (Clojure)** — canonical replay, state transitions, invariants.
2. **Adversarial bridge (Python)** — optional orchestration layer used when adversarial strategy generation/live stepping is required.

> Python is not the core runtime contract owner. Clojure is authoritative.

---

## Authority Model

- Clojure replay/protocol model is source of truth for execution and invariants.
- gRPC wire format is JSON over unary RPC methods.
- Wire keys are `snake_case`; Clojure internally uses kebab-case keywords.

Reference points:
- `src/resolver_sim/server/grpc.clj` (`snake->kw`, `kw->snake`)
- `src/resolver_sim/contract_model/replay.clj`
- `src/resolver_sim/protocols/protocol.clj`

---

## Key Mapping Contract

### Wire JSON ↔ Clojure internal

| Wire JSON key | Clojure key |
|---|---|
| `session_id` | `:session-id` |
| `initial_block_time` | `:initial-block-time` |
| `protocol_params` | `:protocol-params` |
| `trace_entry` | `:trace-entry` |
| `world_view` | `:world-view` |
| `workflow_id` | `:workflow-id` |
| `is_release` | `:is-release` |
| `event_id` | `:event-id` |
| `hop_id` | `:hop-id` |

Rule:
- `snake_case -> kebab-case keyword` on parse.
- `kebab-case keyword -> snake_case` on stream.

Keyword values are also normalized to snake_case strings on wire.

Accessors: `protocols/sew/compat` provides `wf-id`, `event-id`, `hop-id`, and `event-param` with snake_case aliases.

---

## Scenario Replay Contract (Core)

Canonical Clojure replay scenario shape (`replay.clj`):
- `:schema-version`
- `:scenario-id`
- `:agents`
- `:protocol-params`
- `:initial-block-time`
- `:events`

Event core fields:
- `:seq`, `:time`, `:agent`, `:action`, `:params`

Replay results may include:
- `:world-checkpoints` — `{seq → full-world}` captured immediately before each event is dispatched; used by SPE fork replay. Default retention is `:decision-nodes-only` (strategic action seqs only); override via `:flags {:world-checkpoint-policy :retain-all|:omit}`.

Exported trace fixtures (`trace-export`) surface replay idempotence on each step:
- `attributes.idempotency` — `no-op-duplicate` or `applied-once` when `:extra {:idempotency ...}` is present
- `attributes.event_id` / `attributes.hop_id` — from scenario params when provided
- `metadata.idempotency` — summary of deduped step seqs when any no-op duplicates occurred

---

## Replay idempotence params (optional)

Optional params in `:params` for external log / reorg replay:

| Param | Required? | Purpose |
|---|---|---|
| `event-id` | Optional in deterministic scenarios; recommended for external log replay | Logical transaction identifier; activates replay-boundary dedupe for `replay-sensitive-actions` |
| `hop-id` | Optional | Disambiguates escalation hops when the same `event-id` legitimately appears at multiple levels |

When `event-id` is absent, duplicate events are handled by **business-logic guards** (may reject rather than no-op). See `docs/testing/IDEMPOTENCE_CHECKLIST.md`.

Dedupe op-key (Sew): `[:sew :replay-dedupe action agent workflow-id slash-id hop-scope event-id]`

Implementation: `protocols/sew.clj` (`dispatch-action` → `contract-model.idempotency/apply-once`).

**External-log ingestion:** set `:flags {:require-event-id? true}` or use `external-log-replay-flags`. Replay-sensitive actions without `event-id` are rejected with `:missing-event-id` (deterministic scenarios without ids are unaffected when the flag is off).

Reference scenario: `scenarios/S64_replay-event-id-dedupe.json`.
Fork + continuation reference: `scenarios/S65_spe-fork-event-id-inheritance.json`.

---

## Fork replay boundary (SPE counterfactuals)

Counterfactual evaluation (`scenario/subgame_counterfactual.clj`) forks via `replay/resume-from-snapshot`:

| Dimension | Default policy |
|---|---|
| Fork world source | `:world-checkpoints` at decision `:seq` |
| Continuation event shape | `replay/trace-entry->replay-event` (strips trace metadata) |
| Event identity | `:inherit-from-main-trace` — continuations keep main-line `:params` including `event-id` |
| Idempotency state | `:inherit-checkpoint` — `:idempotency/applied` reflects only pre-fork events |
| Stale continuations | Tagged `:fork/stale-continuation` when business guards reject |

Tree expansion is opt-in: `:enable-tree-expansion? true` in SPE config.

---

## Workflow IDs

Workflow IDs are sequential integers assigned by creation order:
- First `create_escrow` → ID `0`
- Second `create_escrow` → ID `1`, etc.

Use integers directly in `:params/:workflow-id`. There is no alias resolution.

---

## Python Adversarial Bridge Contract

Python bridge should emit snake_case payloads:
- scenario keys: `schema_version`, `scenario_id`, `initial_block_time`
- event params: `workflow_id`, `is_release`
- withdrawal params: `amount` for `withdraw_stake`

### `withdraw_stake` action contract

- Action: `withdraw_stake`
- Caller: resolver identity (agent address)
- Params: `{ "amount": <positive integer> }`
- Accepted when:
  - caller has sufficient resolver stake, and
  - caller is not currently assigned as dispute resolver for any active `:disputed` escrow.
- Rejected with:
  - `invalid_amount`
  - `insufficient_stake`
  - `active_disputes_block_withdrawal`

When loading legacy adversarial fixtures, Python may normalize accepted aliases/forms before replay via gRPC.

---

## Change Policy

If any interface key/alias behavior changes:

1. Update this document in the same PR.
2. Update compatibility tests (`python/tests/test_interface_contract.py`).
3. Keep integration-only behavior separate from non-integration compatibility tests.

---

## Scenario Generation Boundary (Canonical Ownership)

This section is normative and exists to prevent semantic drift.

Formal architecture record:
- `docs/architecture/ADR-0003-canonical-scenario-generation-boundary.md`

### Canonical owner: Clojure

The following concerns are **owned by Clojure** and must be implemented in
`src/resolver_sim/*`:

- Scenario generation semantics (`src/resolver_sim/generators/*`)
- Action validity and eligibility against protocol state
- Stateful sequence progression and deterministic replay compatibility
- Adversarial profile semantics used for canonical fixture generation
- Trace-end mechanism/equilibrium evaluation (`scenario/equilibrium.clj`)

Authoritative paths include:
- `src/resolver_sim/generators/actions.clj`
- `src/resolver_sim/generators/stateful.clj`
- `src/resolver_sim/generators/adversarial.clj`
- `src/resolver_sim/generators/scenario.clj`
- `src/resolver_sim/generators/equilibrium.clj`

### Python role: orchestration/bridge only

Python under `python/sew_sim/*` is an adapter layer and must be limited to:

- gRPC session orchestration and integration harnesses
- live/adaptive experiment drivers
- external tooling and reporting wrappers
- wire-format normalization and compatibility glue

Python must **not** become a second canonical source for protocol-stateful
scenario semantics.

### Anti-regression rules

1. **No shadow protocol logic in Python generation paths**
   - If an action validity rule depends on protocol state, it belongs in Clojure.

2. **Clojure-first for new scenario semantics**
   - New action families, timing rules, or adversarial profiles must land in
     `src/resolver_sim/generators/*` first.

3. **Determinism contract**
   - Seeded Clojure generation must remain deterministic (same seed => same events).

4. **Replay compatibility contract**
   - Generated scenario output must remain compatible with
     `resolver-sim.contract-model.replay/replay-scenario`.

5. **Cross-language contract tests when boundary changes**
   - Any boundary change must include updates to docs + tests (Clojure and Python where relevant).

### Review checklist (PR guardrail)

For PRs touching generation or adversarial logic, confirm:

- [ ] Canonical semantics were added/changed in Clojure, not Python
- [ ] Replay/invariant tests still pass for generated scenarios
- [ ] Seed determinism is preserved
- [ ] Interface contract docs updated if behavior changed


---

## System Diagram

> Preserved from `docs/architecture.md`.

```
┌─────────────────────────────────────────────────────────────┐
│  Python adversarial layer                                    │
│                                                              │
│  invariant_suite.py          eth_failure_modes.py            │
│  (scenarios, harness)        (attack agents)                 │
│                │                                             │
│                │  gRPC (port 7070)                           │
└────────────────┼────────────────────────────────────────────┘
                 │
┌────────────────▼────────────────────────────────────────────┐
│  Clojure gRPC server  (src/resolver_sim/server/)             │
│                                                              │
│  Session API: StartSession / Step / DestroySession            │
│                │                                             │
│  contract_model/replay.clj  ← protocol-agnostic kernel       │
│                │                                             │
│  protocols/sew.clj               ← SewProtocol adapter           │
│    protocols/sew/state_machine.clj ← escrow state transitions    │
│    protocols/sew/lifecycle.clj     ← create → dispute → resolve  │
│    protocols/sew/invariants.clj    ← 30+ post-condition checks   │
│    protocols/sew/resolution.clj    ← DR1/DR2/DR3 resolution      │
│    protocols/sew/authority.clj     ← resolver authorization      │
│    protocols/sew/accounting.clj    ← fee and profit calculations │
│    protocols/sew/runner.clj        ← top-level trial runner      │
└─────────────────────────────────────────────────────────────┘
```
