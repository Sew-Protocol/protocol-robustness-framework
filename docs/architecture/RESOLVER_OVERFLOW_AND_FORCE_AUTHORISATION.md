# Resolver Overflow and Force-Authorisation

Two exceptional liveness mechanisms for dispute resolution when the primary
resolver path is unavailable or at capacity.

---

## 1. Resolver Overflow

### What it is

A governance-authorized failover mechanism. When a primary resolver is
overcapacity (or otherwise unavailable), governance can activate an overflow
record that allows designated failover resolvers to resolve disputes assigned
to the overloaded resolver.

### Why it's needed

Without overflow, a resolver at capacity creates a liveness deadlock: disputes
assigned to that resolver cannot be resolved, funds remain locked, and the
protocol stalls. Overflow provides a governance-controlled escape hatch
without requiring permanent resolver reassignment.

### Where it's implemented

| Component | File | Role |
|---|---|---|
| Overflow authorization logic | `protocols_src/resolver_sim/protocols/sew/authority.clj:154-190` | `authorized-overflow-resolver?` and `active-overflows-for` |
| Governance activation action | `protocols_src/resolver_sim/protocols/sew.clj:487-545` | `apply-action "activate-resolver-overflow"` |
| Overflow resolution action | `protocols_src/resolver_sim/protocols/sew.clj:670-721` | `apply-action "execute-overflow-resolution"` |
| Overflow record type | `protocols_src/resolver_sim/protocols/sew/types.clj:272-274` | World state keys `:resolver-overflows`, `:next-overflow-id` |
| Invariant coverage | `protocols_src/resolver_sim/protocols/sew/invariants.clj:1222` | `dispute-resolution-path-exists?` includes active overflows |
| Execution provenance | `protocols_src/resolver_sim/protocols/sew/resolution.clj:611` | `apply-resolution-transition` with `:resolution-source :resolver-overflow` |
| Scenario tests | `scenarios/edn/DR-O-001-*`, `DR-O-002-*` | Basic success and cap-exhaustion paths |
| Unit tests | `protocols_src/test/.../authority_test.clj:148-275` | 15 authorization-scoping tests |

### Overflow record shape

```clojure
{:overflow-id         integer        ; unique identifier
 :resolver            address        ; primary resolver being overflowed
 :reason              keyword        ; reason for overflow activation
 :authorized-by       address        ; governance actor who activated
 :created-at          integer        ; block timestamp of creation
 :starts-at           integer        ; when overflow window opens
 :expires-at          integer        ; when overflow window closes
 :max-workflows       integer        ; cap on number of resolutions
 :failover-resolvers  #{address}     ; set of authorized failover actors
 :used-workflows      #{wf-id}       ; workflows already resolved under this overflow
 :status              keyword        ; :active, :exhausted, or :revoked
 :authorization/provenance  map      ; governance authorization provenance
 :authorization/last-provenance map ; most recent provenance
 :authorization/last-action   string ; most recent action
 :authorization/history  [map]      ; full provenance history}
```

### How it works (end-to-end)

1. **Detection** — A resolver reaches capacity (`current-active >= max-concurrent`).
   Disputes destined for that resolver start failing with `:resolver-capacity-exceeded`.

2. **Governance activation** — A governance actor calls `activate-resolver-overflow`
   with the overloaded resolver address, a reason, and (optionally) a list of
   failover resolvers. The action is gated by `with-governance-actor` and
   produces a signed governance authorization envelope.

   Default policy (from `:resolver-overflow-policy` in context):
   - `:max-workflows` — 500
   - `:default-duration` — 3600 seconds
   - `:allowed-reasons` — `#{:resolver-overcapacity}`

   A warning is logged if the resolver is not actually at capacity.

3. **Failover resolution** — A listed failover resolver calls
   `execute-overflow-resolution` with a workflow-id and overflow-id.
   The authorization check (`authorized-overflow-resolver?`) verifies:

   - Overflow record exists and status is `:active`
   - Current time is within `[starts-at, expires-at)`
   - `count(used-workflows) < max-workflows`
   - Workflow-id not already in `used-workflows`
   - Caller is in `failover-resolvers`
   - Workflow's `dispute-resolver` matches the overflow's `resolver`
   - Workflow state is `:disputed`

4. **Execution** — On success, delegates to `apply-resolution-transition` with
   `:resolution-source :resolver-overflow`. The overflow record is updated:
   `used-workflows` gains the workflow-id, and `status` transitions to
   `:exhausted` if the cap is reached. Execution provenance (schema version
   `execution-provenance.v1`, type `:forced-capacity-failover`) is written
   to both the escrow resolution and the overflow record history.

5. **Termination** — An overflow ends when `expires-at` is reached, the
   `max-workflows` cap is exhausted (`:exhausted` status), or (in tests)
   via `:revoked` status (no action exists yet for governance revocation).

### Authorization scope

The overflow authorization is scoped by all of:
- **Actor** — only `failover-resolvers` may execute
- **Workflow** — only workflows whose primary resolver matches the overflow's resolver
- **Time** — bounded window `[starts-at, expires-at)`
- **Quantity** — capped at `max-workflows`
- **Replay** — single-use per workflow (ids in `used-workflows` are rejected)
- **State** — workflow must be in `:disputed` state

---

## 2. Force-Authorisation

### What it is

An explicit, scoped, expiring, single-use authorization record for exceptional
protocol actions. Unlike overflow (which delegates to pre-listed failover
resolvers), force-authorisation is a general-purpose governance-authorized
override that binds to a specific workflow, action, token, amount, and direction.

### Why it's needed

Some situations require protocol-level intervention beyond what overflow can
cover: the primary resolver is not just overcapacity but frozen, the circuit
breaker is active, the resolver is unavailable, or a governance-ordered
correction is needed. Force-authorisation provides a replayable, auditable
escape hatch with cryptographic evidence at every step.

### Where it's implemented

| Component | File | Role |
|---|---|---|
| Grant action | `protocols_src/resolver_sim/protocols/sew.clj:547-619` | `apply-action "grant-force-authorization"` |
| Revoke action | `protocols_src/resolver_sim/protocols/sew.clj:621-663` | `apply-action "revoke-force-authorization"` |
| Execute action | `protocols_src/resolver_sim/protocols/sew.clj:665-749` | `apply-action "execute-force-authorized-action"` |
| Authorization record | `protocols_src/resolver_sim/protocols/sew/types.clj:275-277` | World state keys `:force-authorisations`, `:next-force-authorisation-id` |
| Scope validation | `protocols_src/resolver_sim/protocols/sew/accounting.clj:327-361` | `adjust-held` scope-hash check and consumption |
| Evidence | Built into each action | `:force-authorisation-granted`, `-revoked`, `-executed` |
| Unit tests | `protocols_src/test/.../accounting_test.clj:236-415` | 4 force-auth scope and consumption tests |

### Force-authorisation record shape

```clojure
{:authorization/id           string       ; unique identifier "fa-<n>"
 :authorization/type         :force-authorisation
 :authorization/source       :governance
 :authorization/status       :active | :consumed | :revoked
 :workflow-id                integer      ; bound to specific workflow
 :allowed-action             string       ; action authorized (only "execute-resolution")
 :authorization/scope        map          ; immutable grant-time custody movement
 :authorization/scope-hash   string       ; domain hash of the exact scope
 :nonce                      string       ; replay-protection nonce (= auth-id)
 :starts-at                  integer      ; earliest valid execution time
 :expires-at                 integer      ; latest valid execution time (nil = no expiry)
 :created-at                 integer      ; grant timestamp
 :created-by                 address      ; governance actor who granted
 :reason                     keyword      ; reason for force-authorisation
 :consumed?                  boolean      ; execution guard
 :authorization/provenance   map          ; governance authorization provenance
 :authorization/last-provenance map
 :authorization/last-action  string
 :authorization/history      [map]        ; full provenance history
 ;; Set on execution:
 :executed-by                address
 :executed-at                integer
 :execution/is-release       boolean
 :execution/provenance       map          ; structured execution provenance
 :execution/last-provenance  map
 :execution/last-action      string
 :execution/history          [map]}
```

### How it works (end-to-end)

1. **Grant** — Governance calls `grant-force-authorisation` with a disputed
   workflow-id, reason, settlement direction (`:is-release`), and timing
   parameters (`:starts-at`, `:expires-at` XOR `:duration`). The action is gated
   by `with-governance-actor`, derives the exact custody movement from the
   current escrow, and persists both the immutable scope and its domain hash in
   `world[:force-authorisations]`. A grant cannot be created for a nonexistent
   or non-disputed workflow, an unsupported action/reason, or an invalid time
   window. Emits `:force-authorisation-granted` evidence.

2. **Revoke** (optional) — Governance calls `revoke-force-authorization` with an
   auth-id. Sets status to `:revoked`. Emits `:force-authorisation-revoked`
   evidence.

3. **Execute** — Any resolved actor (not governance-gated) calls
   `execute-force-authorized-action`. Eight checks must pass:

   - Authorization record exists
   - Status is `:active`
   - `:consumed?` is `false`
   - Workflow-id matches
   - Allowed action matches (`execute-resolution`)
   - Current time >= `:starts-at` (not-yet-started rejected)
   - Current time < `:expires-at` (expired rejected)
   - Not already consumed in accounting layer (`:force-authorisations/consumed`)

   On success, derives the execution scope and requires it to equal the
   immutable grant scope and hash (`:authorization/id`, `:authorization/type`,
   `:held/direction`, `:token`, `:amount`, `:held/account`, `:owner/address`,
   `:held/reason`, `:held/workflow-id`) before delegating to
   `apply-resolution-transition` with `:resolution-source :force-authorised`.

   Execution records provenance but leaves the grant active while the resolution
   awaits settlement. At finalization, the accounting layer independently reloads
   the live authorization record and verifies that it exists, is active and
   in-window, has not been consumed, and commits to the exact actual held
   adjustment. It then atomically records the adjustment and consumption (dual
   guard: record flag `:consumed?` and accounting-level
   `:force-authorisations/consumed` map). `:force-authorisation-executed`
   evidence records the execution event; the linked held adjustment is the
   authoritative consumption record.

### Authorization scope (scope-hash)

The scope-hash cryptographically binds the authorization to:

```
{:authorization/id     — unique authorization identifier
 :authorization/type   — :force-authorisation
 :held/direction       — :out (sub-held)
 :token                — token address
 :amount               — exact custody amount (:amount-after-fee)
 :held/account         — :escrow-principal
 :owner/address        — recipient of the settlement
 :held/reason          — :force-authorised-release or :force-authorised-refund
 :held/workflow-id     — workflow being resolved}
```

This is a **grant-time commitment**, not an execution-time checksum. It proves
not just *that* governance approved an override, but *exactly which custody
movement* was authorized — which token, how much, to whom, for which escrow.
A release-scoped grant cannot later be executed as a refund.

---

### Lifecycle state machine

A force-authorisation has persisted states `:active`, `:consumed`, and
`:revoked`. Its effective state is additionally **expired** when the current
block time is at or after `:expires-at`; expiry is intentionally derived rather
than a mutable state transition. Only an active, in-window record can execute.

```text
active --execute/atomic custody adjustment--> consumed
active --governance revoke------------------> revoked
active --time reaches expiry----------------> effectively expired
```

`consumed` and `revoked` are terminal. A consumed record must have exactly one
matching held adjustment and consumption-registry entry; the adjustment must
carry the record's immutable scope hash. An active record must have neither.
These conditions are checked by the
`:force-authorisations-lifecycle-consistent` world invariant.

### Held-custody enforcement

`add-held` and `sub-held` are the canonical custody mutation primitives. Every
reason governed by the held-position policy derives a custody account and a
position identifier. An outflow must satisfy both of these conditions before
it is appended to the ledger:

1. the token-wide `:total-held` balance covers the amount; and
2. the derived custody position covers the amount.

For address-scoped reasons, `:owner/address` is mandatory. A caller cannot
supply an account conflicting with the reason policy, and an adjustment with
incomplete position scope is rejected. These checks prevent an outflow for one
workflow or bond position from being funded by another position that happens
to hold the same token. The `:terminal-workflow-custody-closed` invariant also
requires the terminal workflow's `:escrow-principal` position to be zero;
deferred yield remains represented by the yield-liability model rather than as
residual principal custody.

Each custody artifact is content-addressed and includes the prior artifact hash,
forming an ordered, tamper-evident custody chain. Forced artifacts retain the
authorization ID, scope hash, workflow, action, and source needed to establish
which grant justified the movement.

When `:held-adjustments/complete?` is declared, replay reconstruction and the
artifact's deterministic closed-form checks are mandatory. A complete ledger
therefore cannot pass while its materialized views, artifact hashes, local
deltas, or ordered replay are inconsistent.

### Forensic bundle witness

For any run with force-authorisation state, the bundle root contains a
scenario-keyed `:protocol/state` witness with authorization records,
consumption records, and canonical forced held adjustments. Scenario keys avoid
collisions from deterministic local IDs such as `fa-0`. It commits to each
section in `:protocol/state-hashes`, including `:held-adjustments/hash`, and to
the JSON-native witness with `:protocol/state-witness-hash` (standard SHA-256
of canonical sorted JSON). The latter is independently recomputed by the Python
validator. `forensic:validate` requires this witness when force-authorisation
evidence exists and verifies authorization → consumption → adjustment links,
as well as their agreement with grant/revoke/execute evidence.

---

## 3. Authorization Sources — Comparison

| Aspect | `with-governance-actor` | `authorized-overflow-resolver?` | Force-authorisation |
|---|---|---|---|
| **Gates** | `activate-resolver-overflow`, `grant-force-authorization`, `revoke-force-authorization`, etc. | `execute-overflow-resolution` | `execute-force-authorized-action` |
| **Who is authorized** | Any agent satisfying `governance-pred` (`:full` mode = all agents; `:restricted` mode = governance role) | Actors listed in `:failover-resolvers` on the overflow record | Any resolved actor (the authorization record itself provides authority) |
| **What it authorizes** | Creating/altering protocol governance state | Resolving a specific workflow under a specific overflow | Executing a pre-authorized action bound to a specific workflow and amount |
| **Provenance schema** | `governance-authorization.v1` | `execution-provenance.v1` | `force-authorisation.v2` |
| **Provenance type** | `:governance` | `:forced-capacity-failover` | `:force-authorisation` |
| **Evidence** | Captured by downstream action handlers | Captured by `apply-resolution-transition` | Explicit evidence at grant, revoke, AND execute |
| **Scope mechanism** | Role-based (governance actor) | Record-based (overflow record with failover list, cap, time window) | Record-based + cryptographic scope-hash binding token, amount, direction, recipient |
| **Consumption model** | N/A (governance actions modify state once) | Per-workflow dedupe via `used-workflows` set; cap via `max-workflows` | Dual guard: record `:consumed?` flag AND accounting-level `:force-authorisations/consumed` map |
| **Time bounds** | N/A | `:starts-at` / `:expires-at` window | `:starts-at` / `:expires-at` window |
| **Use case** | Day-to-day governance operations | Resolver overcapacity failover | Exceptional recovery (frozen resolver, circuit breaker, governance correction) |

### When to use each

- **`with-governance-actor`** — Standard governance actions: activating overflow,
  rotating resolvers, pausing protocol, updating fees. The actor's role is the
  sole authorization check.

- **`authorized-overflow-resolver?`** — Resolver capacity failover only.
  Requires a pre-existing overflow record activated by governance. The
  authorization is scoped to specific failover actors, a specific primary
  resolver, a time window, and a resolution cap.

- **Force-authorisation** — Exceptional override when the normal authorization
  path cannot work (resolver frozen, circuit breaker active, resolver
  unavailable, governance-ordered correction). Requires a pre-existing
  authorization record granted by governance. The authorization is bound to a
  specific workflow, token, amount, and direction via cryptographic scope-hash.
  Full evidence trail at every step.

---

## 4. Interaction Between Overflow and Force-Authorisation

Overflow and force-authorisation are complementary, not redundant:

- **Overflow** solves the common case: resolver is busy but functional.
  Governance pre-authorizes a list of alternates, and any of them can step in
  until the cap or time window expires.

- **Force-authorisation** solves the exceptional case: resolver is frozen,
  circuit breaker is active, or governance needs to order a specific outcome.
  The authorization is workflow-scoped and amount-bound — far narrower than
  overflow.

In practice: overflow handles routine capacity spikes; force-authorisation
handles incidents and recovery.

---

## 5. Known Gaps

| Gap | Description |
|---|---|
| No `revoke-resolver-overflow` action | `:revoked` status exists and is tested but no action sets it. Overflow can only expire or exhaust. |
| Generic error on cap exhaustion | Overflow cap exhaustion returns `:not-authorized-resolver` (shared with other resolver auth failures) instead of a dedicated `:overflow-cap-exhausted`. |
| No automatic overflow trigger | Activation is governance-only. No automatic trigger when `resolver-at-capacity?` becomes true. |
| No overflow queue | Disputes arriving while a resolver is at capacity are rejected. No pending queue. The overflow mechanism only handles disputes *already raised*. |
