# Dispute Mechanism Attack Surface — Research Report

**Audience:** Protocol engineers, security researchers.
**Date:** 2026-06-19
**Coverage:** Findings discovered through dispute-mechanism attack-pattern analysis.

---

## Finding 1 — Challenge-Bond-Exceeds-Escrow-Value

### Status
**Confirmed vulnerability** — the protocol's challenge bond default (100) exceeds
the value of small escrows, making challenge economically irrational and
enabling fraudulent outcomes to become final.

### Priority
1 (highest) — dispute path is permanently or economically blocked.

### SEW-native description
When `challenge-bond-bps` and `appeal-bond-amount` are both unset in
protocol-params, `calculate-challenge-bond-amount` returns a default of 100
units (`payoffs.clj:55`). For any escrow with `amount-after-fee < 100`, the
cost to challenge a fraudulent resolution exceeds the escrow's value. A
rational actor will not pay 100 to challenge a 50 escrow. The appeal window
expires, the keeper executes the settlement, and the fraudulent outcome
becomes final with zero on-chain fault signals.

### External pattern that inspired it
UMA / optimistic oracle: assertion is accepted because no bonder finds it
economic to dispute the assertion within the window. SEW's version: a
resolution is executed because no challenger finds it economic to post the
challenge bond within the appeal window.

### Relevant files
- `src/resolver_sim/economics/payoffs.clj:41-55` — bond default 100
- `src/resolver_sim/protocols/sew/resolution.clj:524-615` — challenge-resolution
- `src/resolver_sim/protocols/sew/accounting.clj:194-223` — post-appeal-bond
  (no balance enforcement on caller)

### Minimal failing sequence
1. Deploy SEW with default params (no `challenge-bond-bps`, no `appeal-bond-amount`)
2. Buyer creates escrow: value=50, custom-resolver=0xmalicious
3. Seller raises fraudulent dispute
4. Malicious resolver executes resolution: is-release=false (refunds to seller)
5. Honest buyer has 50 USDC in escrow, but challenge bond would be 100 USDC
6. Buyer cannot afford challenge — no rational actor challenges
7. Appeal window expires
8. Keeper executes settlement: seller receives 50 USDC, buyer gets nothing
9. No invariant is violated. No evidence of foul play is captured beyond the
   fraudulent resolution hash.

### Why current tests miss it
1. No existing scenario sets escrow value < 100 with default challenge bond
2. `post-appeal-bond` does not enforce caller balance — the simulation credits
   the bond without deducting from the caller, so the vulnerability is
   invisible in replay
3. S-DR-075 tests resolver-side economics; no scenario tests challenger-side
   economics
4. The `:conservation-of-funds` invariant passes because the bond is "minted"
   into protocol-held — the simulation does not enforce real-world budget

### Proposed scenario map (pseudocode)
```clojure
{:scenario-id "s-dr-080-challenge-bond-exceeds-value"
 :purpose :theory-falsification
 :tags ["suite/dispute-resolution" "coverage/economic-liveness"]
 :agents [{:id "buyer"   :address "0xbuyer"   :strategy "honest"     :balance 50}
         {:id "seller"  :address "0xseller"  :strategy "malicious"}
         {:id "malicious-resolver" :address "0xmalicious" :role "resolver" :strategy "malicious"}
         {:id "challenger" :address "0xchallenger" :strategy "honest" :balance 50}]
 :protocol-params {:resolver-fee-bps 150
                    :appeal-window-duration 120
                    :max-dispute-duration 2592000}
 :events [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
           :params {:token "USDC" :to "0xseller" :amount 50
                    :custom-resolver "0xmalicious"}}
          {:seq 1 :time 1060 :agent "seller" :action "raise_dispute"
           :params {:workflow-id 0}}
          {:seq 2 :time 1120 :agent "malicious-resolver" :action "execute_resolution"
           :params {:workflow-id 0 :is-release false
                    :resolution-hash "0xfraudulent-refund"}}
          ;; Appeal opens at t=1120, closes at t=1240
          ;; Challenge bond = 100 (default min) > escrow value = 50
          ;; No one challenges — economically irrational
          {:seq 3 :time 1250 :agent "keeper" :action "execute_pending_settlement"
           :params {:workflow-id 0}}]}
```

### Expected invariant failure
A new economic invariant is needed:
```clojure
:challenge-bond-vs-escrow-value
;; Each escrow must have amount-after-fee >= challenge-bond-minimum
;; otherwise no rational challenger will challenge a fraudulent resolution.
```
This invariant would hold during `create_escrow` — the protocol should reject
escrows where the default challenge bond exceeds the escrow value, OR the
deployer must explicitly configure a lower bond via `challenge-bond-bps`.

### Evidence required
- challenge bond amount for this escrow
- escrow value (amount-after-fee)
- timestamps showing window expiry
- settlement outcome
- evidence of "no challenge occurred" (e.g., empty challengers list)

### Observability gap
The framework's `post-appeal-bond` at `accounting.clj:194` does not enforce
that the caller has sufficient balance. The simulation can only model this
vulnerability if balance enforcement is added or if a new invariant checks
bond-vs-value at creation time.

### Suggested fix direction
**Short term:** Add a scenario with low-value escrow that demonstrates the bad
outcome becoming final due to uneconomic challenge cost. Mark as XFAIL
(theory-falsification) to surface the concern.

**Long term:** Either:
1. Add balance enforcement to `post-appeal-bond` so the simulation checks
   caller solvency
2. Add a protocol-level guard during `create_escrow` that rejects escrows
   where default challenge bond exceeds escrow value, unless the deployer
   explicitly configures a lower bond threshold
3. Document that the challenge bond default (100) must be configured per
   deployment to match expected escrow values

---

---

## Finding 2 — Slash-Succeeds-Victim-Not-Made-Whole

### Status
**Confirmed vulnerability** — the protocol detects and punishes resolver fraud
but does NOT repair the victim's escrow state. The slashed proceeds flow to
insurance/protocol/reserves, not the victim.

### Priority
2 — fraud is detected and slashed but victim is not made whole.

### SEW-native description
After a fraudulent resolution is discovered and the resolver is successfully
slashed (via `propose-fraud-slash` → `execute-fraud-slash`), the escrow
settlement has already been executed. The victim (the party who lost the
escrow due to the fraudulent ruling) receives nothing from the slash
proceeds. The slashed amount at `resolution.clj:1045` is deducted from the
resolver's stake and distributed via `calculate-prorata-slash-allocation`
(`payoffs.clj:124`) to insurance pool (default 50%), protocol fees (default
30%) and retained reserves (default 20%). No mechanism credits the victim's
`claimable` balance from the slash proceeds.

### External pattern that inspired it
Kleros / UMA: an oracle is disputed and slashed, but the original erroneous
resolution is not automatically corrected. The harmed party must initiate a
new dispute or claim from a separate insurance mechanism. SEW's version: the
protocol correctly identifies fraud and punishes the resolver, but the escrow
state remains economically wrong for the victim.

### Relevant files
- `src/resolver_sim/protocols/sew/resolution.clj:1005-1074` — execute-fraud-slash
- `src/resolver_sim/economics/payoffs.clj:124-200` — prorata allocation
- `src/resolver_sim/protocols/sew/accounting.clj:106-127` — record-claimable
  (victim is never credited here)

### Minimal failing sequence
1. Buyer creates escrow (5000 USDC, custom-resolver=0xmalicious)
2. Seller raises fraudulent dispute
3. Malicious resolver executes resolution: is-release=false (refunds to seller)
4. Settlement executes — seller receives 5000 USDC, buyer gets nothing
5. Governance proposes fraud slash against malicious resolver (amount=500)
6. Slash executes — resolver loses 500 USDC (of 10000 staked)
7. Slash distribution: 250 → insurance, 150 → protocol, 100 → retained
8. **Buyer still has zero claimable balance — victim is not made whole**
9. No invariant is violated. Evidence captures the slash but not the repair
   gap.

### Why current tests miss it
1. No existing scenario checks the victim's balance AFTER a successful slash
   on an executed settlement
2. The `:conservation-of-funds` invariant passes because the funds are
   conserved within the protocol (stake → insurance/protocol/retained)
3. The victim's loss is "external" to the protocol's accounting — the escrow
   was settled correctly, the slash was executed correctly, but the
   combination leaves the victim unrepaired
4. S-DR-063 and S-DR-064 test slash appeal lifecycle but do not check victim
   recovery
5. The `:fraud-slash-executions-accounted` invariant only checks that slash
   totals match, not that victims are compensated

### Proposed scenario map (pseudocode)
```clojure
{:scenario-id "s-dr-081-slash-victim-not-repaired"
 :purpose :theory-falsification
 :tags ["suite/dispute-resolution" "coverage/state-repair"]
 :agents [{:id "buyer"   :address "0xbuyer"   :strategy "honest"}
         {:id "seller"  :address "0xseller"  :strategy "malicious"}
         {:id "malicious-resolver" :address "0xmalicious" :role "resolver"
          :strategy "malicious"}
         {:id "governance" :address "0xgov" :role "governance"}
         {:id "keeper" :address "0xkeeper" :role "keeper"}]
 :protocol-params {:resolver-fee-bps 150
                    :appeal-window-duration 60
                    :max-dispute-duration 2592000}
 :events [{:seq 0 :time 1000 :agent "malicious-resolver" :action "register_stake"
           :params {:amount 10000 :token "USDC"}}
          {:seq 1 :time 1010 :agent "buyer" :action "create_escrow"
           :params {:token "USDC" :to "0xseller" :amount 5000
                    :custom-resolver "0xmalicious"}}
          {:seq 2 :time 1070 :agent "seller" :action "raise_dispute"
           :params {:workflow-id 0}}
          {:seq 3 :time 1130 :agent "malicious-resolver" :action "execute_resolution"
           :params {:workflow-id 0 :is-release false
                    :resolution-hash "0xfraud"}}
          ;; Settlement executes — buyer loses 5000 USDC
          {:seq 4 :time 1220 :agent "keeper" :action "execute_pending_settlement"
           :params {:workflow-id 0}}
          ;; Governance detects fraud, proposes slash
          {:seq 5 :time 1280 :agent "governance" :action "propose_fraud_slash"
           :params {:workflow-id 0 :resolver-addr "0xmalicious" :amount 500}}
          ;; Slash executes — resolver loses 500 USDC
          {:seq 6 :time 1380 :agent "governance" :action "execute_fraud_slash"
           :params {:workflow-id 0}}]
 ;; Post-conditions:
 ;;   buyer.claimable = 0  (victim not made whole)
 ;;   resolver.stake = 9500  (lost 500 to slash)
 ;;   insurance + protocol + retained = 500  (slash proceeds absorbed)
 ;;   escrow state does not reflect the fraud detection}
```

### Expected invariant failure
A new invariant is needed:
```clojure
:fraud-slash-compensates-victim
;; When a fraud slash is executed against a resolver who ruled on a
;; specific escrow, the victim of that ruling must have their claimable
;; balance increased by at least the amount they lost.
```
This invariant would FAIL for the current protocol — the victim's claimable
balance is never credited after a slash.

### Evidence required
- pre-slash and post-slash claimable balances for the victim
- slash allocation showing insurance/protocol/retained distribution
- escrow settlement outcome (who received what)
- resolver stake before and after

### Observability gap
The `claimable` balance is tracked in the world state but not surfaced in any
researcher-readable artifact (`claimable-summary.json` does not exist). The
victim's balance MUST be explicitly checked — no invariant or test currently
does this.

### Suggested fix direction
Add an `:repair-victim` step to `execute-fraud-slash` that credits the
victim from the slash proceeds before distributing the remainder. This is a
protocol design question — should slash proceeds prioritize victim
compensation over insurance pool funding?

---

---

## Finding 3 — Stake-Escrow-Ratio-Unbounded

### Status
**Confirmed vulnerability** — a resolver with minimal stake can resolve an
arbitrarily large escrow when `bond-bps` (or `resolver-bond-bps`) is not
configured. The maximum penalty (50% of stake) is dwarfed by the fraud
profit (full escrow value).

### Priority
3 — attacker profit exceeds maximum punishment.

### SEW-native description
The `create-escrow` guard at `lifecycle.clj:318-323` checks the resolver's
stake sufficiency ONLY when `bond-bps > 0`:

```clojure
(and resolver (pos? bond-bps) (pos? stake)
     (not (reg/can-handle-escrow? world resolver afa)))
```

When `bond-bps` is 0 (default), a resolver with 100 USDC staked can be
assigned to a 1,000,000 USDC escrow. The max-per-offense cap
(`max-slash-per-offense-bps` defaults to 5000 = 50%) limits the attacker's
maximum punishment to 50 USDC, while the fraud profit is 1,000,000 USDC. The
attacker's profit exceeds maximum punishment by 20,000×. Even with 90%
detection probability, the expected loss (5 USDC) is negligible compared to
the gain.

### External pattern that inspired it
General oracle/dispute system: the security deposit must be proportional to
the value at stake. SEW's version: the resolver bond is optional
(`resolver-bond-bps` defaults to 0), and when absent, no economic link
exists between the resolver's stake and the escrow value they resolve.

### Relevant files
- `src/resolver_sim/protocols/sew/lifecycle.clj:305-328` — create-escrow guard
- `src/resolver_sim/protocols/sew/resolution.clj:894-901` — max-per-offense cap
- `src/resolver_sim/protocols/sew/registry.clj:70-78` — can-handle-escrow?

### Minimal failing sequence
1. Deploy SEW with default params (`resolver-bond-bps: 0`, the default)
2. Malicious resolver registers minimal stake (100 USDC)
3. Malicious seller creates escrow: value=1,000,000 USDC, resolver=0xmalicious
4. Seller raises dispute
5. Malicious resolver rules fraudulently (is-release=false, refunds to seller)
6. Settlement executes — attacker (seller+resolver) extracts 1,000,000 USDC
7. If slashed: max penalty = 50 USDC (50% of 100)
8. Net attacker profit = 1,000,000 − 50 = 999,950 USDC
9. No guard can prevent this — the create-escrow check passed because
   `bond-bps` was 0

### Why current tests miss it
1. No existing scenario sets `bond-bps` (or `resolver-bond-bps`) to 0
   (default) with a resolver whose staked amount is much smaller than the
   escrow value
2. Existing adversarial scenarios (S34-S37, S-DR-075) use reasonable
   stake-to-escrow ratios but never test the unbounded case
3. The `:settlement-principal-boundary` invariant checks that claims do not
   exceed escrow principal — it does NOT check resolver stake vs escrow
   value
4. The `:resolver-bond-mix-valid` invariant checks bond composition but not
   bond-vs-value proportionality

### Proposed scenario map (pseudocode)
```clojure
{:scenario-id "s-dr-082-minimal-stake-large-escrow"
 :purpose :theory-falsification
 :tags ["suite/dispute-resolution" "coverage/economic-security"]
 :agents [{:id "buyer"   :address "0xbuyer"   :strategy "honest"}
         {:id "seller"  :address "0xseller"  :strategy "malicious"}
         {:id "minimal-resolver" :address "0xminimal" :role "resolver"
          :strategy "malicious"}
         {:id "keeper" :address "0xkeeper" :role "keeper"}]
 :protocol-params {:resolver-fee-bps 150
                    :resolver-bond-bps 0
                    :appeal-window-duration 60
                    :max-dispute-duration 2592000}
 :events [{:seq 0 :time 1000 :agent "minimal-resolver" :action "register_stake"
           :params {:amount 100 :token "USDC"}}
          ;; Escrow value 100,000 — far exceeds the 100 stake
          {:seq 1 :time 1010 :agent "buyer" :action "create_escrow"
           :params {:token "USDC" :to "0xseller" :amount 100000
                    :custom-resolver "0xminimal"}}
          {:seq 2 :time 1070 :agent "seller" :action "raise_dispute"
           :params {:workflow-id 0}}
          {:seq 3 :time 1130 :agent "minimal-resolver" :action "execute_resolution"
           :params {:workflow-id 0 :is-release false
                    :resolution-hash "0xfraud"}}
          {:seq 4 :time 1220 :agent "keeper" :action "execute_pending_settlement"
           :params {:workflow-id 0}}]
 ;; Post-conditions:
 ;;   seller.claimable ~= 100,000 (attacker profit)
 ;;   resolver.stake ~= 100 (penalty potential)
 ;;   max-penalty = 50 (50% of stake)
 ;;   attacker-profit / max-penalty ≈ 2000×
 ;;   attack is economically rational at any detection probability < 99.95%}
```

### Expected invariant failure
A new invariant is needed:
```clojure
:resolver-stake-proportional-to-escrow
;; For every escrow, the assigned resolver's registered stake must be
;; >= some fraction of the escrow value (e.g., resolver-stake >= escrow-value
;; or a configurable ratio).
```
This invariant would FAIL for the current default configuration
(`resolver-bond-bps: 0`), revealing the unbounded leverage gap.

### Evidence required
- resolver registered stake
- escrow value (amount-after-fee)
- guard pass/fail at create_escrow time
- max-slash-per-offense calculation
- attacker profit / max-penalty ratio

### Suggested fix direction
Either:
1. Set a non-zero default for `resolver-bond-bps` so the stake-to-escrow
   guard activates automatically
2. Add an invariant that checks stake-proportionality and warn in the
   coverage report when it fails
3. Add a protocol-level guard that rejects creating an escrow where the
   resolver's stake is less than a configurable minimum percentage of the
   escrow value

---

---

## Finding 4 — Challenge-Bond-Escalation-Cost-Griefing

### Status
**Confirmed scenario gap** — a serial challenger pays escalating bond costs
(10% increase per challenge from the same address), while the attacker can
rotate to fresh addresses at no cost. The sybil mitigation asymmetrically
punishes honest repeat challengers.

### Priority
6 — honest challenge is technically possible but economically irrational
(scenario exists but burden is asymmetric).

### SEW-native description
The `challenge-resolution` function at `resolution.clj:572-576` applies
sybil mitigation via `escalation-counts-per-addr`:

```clojure
esc-count (get-in world [:escalation-counts-per-addr caller] 0)
base-bond (payoffs/calculate-challenge-bond-amount ...)
bond-amt  (quot (* base-bond (+ 10000 (* esc-count 1000))) 10000)
```

Each subsequent challenge from the same address costs 10% more. A dedicated
watchdog who monitors an escalation board and challenges N disputed
resolutions pays `base-bond × (1.0 + 0.1 × N)`. An attacker deploying a
sybil of M fresh addresses pays only `base-bond × M` (no escalation penalty
because each address starts at count 0). The honest actor is penalized for
reputation-building; the attacker is not.

### External pattern that inspired it
Kleros / general dispute boards: sybil-resistant appeal costs must not
penalize honest repeat participants. SEW's version: per-address escalation
counting creates a cost asymmetry where the honest watchdog pays more per
challenge than a sybil attacker using fresh addresses.

### Relevant files
- `src/resolver_sim/protocols/sew/resolution.clj:571-576` — escalation bond
- `src/resolver_sim/protocols/sew/resolution.clj:585-590` — escalation count
  tracking

### Minimal failing sequence
1. Honest watchdog challenges 10 fraudulent resolutions from address 0xwatch
   → bond cost for 10th challenge = base-bond × 2.0 (100% premium)
2. Attacker deploys 10 sybil challengers, each challenges 1 resolution
   → bond cost per challenger = base-bond × 1.0 (0% premium)
3. Total cost for honest watchdog = 10 × base-bond × 1.55 (average)
4. Total cost for attacker = 10 × base-bond × 1.0 (average)
5. The honest actor pays 55% more for the same number of challenges

### Why current tests miss it
1. No scenario tests challenge bond accumulation across multiple escrows from
   the same challenger address
2. The sybil mitigation is a protocol feature, but its economic asymmetry is
   not tested

### Proposed scenario map (pseudocode)
```clojure
;; Multi-escrow watchdog challenge — demonstrate asymmetric cost
;; Create 3 escrows, each with a fraudulent resolution.
;; Honest challenger (single address) challenges all 3.
;; Check that bond cost increases by 10% per challenge.
;; Expected: challenge 1 costs 100, challenge 2 costs 110, challenge 3 costs 120
```

### Expected invariant failure
New invariant:
```clojure
:escalation-bond-cost-monotonic
;; Challenge bond costs must increase monotonically per address.
;; (True by construction — not a failure.)
```
And:
```clojure
:escalation-bond-cost-asymmetry
;; The ratio of honest challenger cost to sybil attacker cost
;; must be documented for the protocol deployer.
```

### Suggested fix direction
Documentation and parameter guidance. The sybil mitigation is intentional,
but protocol deployers should be aware that per-address escalation counting
penalizes honest watchdogs relative to sybil attackers. If escalation board
is expected to have dedicated watchdogs, consider a per-escrow-or-per-period
reset instead of a per-address counter.

---

## Finding 5 — Evidence-Cannot-Prove-Vulnerable-Transition

### Status
**Observability gap** — multiple findings (F1–F4 above) exist in the protocol
state machine but are NOT visible in the researcher-readable artifact suite.
A researcher running the full scenario replay sees outcome=:pass and zero
invariant violations, and cannot distinguish "protocol correct, attack
profitable" from "protocol correct, everything fine."

### Priority
7 — evidence cannot prove the vulnerable transition when the state machine
is valid but the economics are wrong.

### SEW-native description
The evidence capture system at `event_evidence.clj:247-307` captures
world-before/world-after hashes and attribution context for every protocol
action. But the evidence artifacts do not surface:
- Economic parameters (bond, stake, escrow value side by side)
- Challenge affordability (bond required vs challenger balance)
- Post-slash victim repair status
- Whether a given invariant was checked and passed or failed
- For theory-falsification scenarios: whether the expected failure was a
  protocol crash or an economic vulnerability

A researcher looking at `evidence-summary.json` sees what happened, not
whether it should have been prevented.

### External pattern that inspired it
General principle: security-critical systems must surface not just "what
happened" but "was this expected?" SEW's evidence captures protocol state
transitions but not the economic control signals that make them safe or
unsafe.

### Relevant files
- `src/resolver_sim/evidence/summary.clj` — current evidence summary
- `src/resolver_sim/io/event_evidence.clj:247-307` — capture infrastructure
- `src/resolver_sim/scenario/dispute_coverage.clj:106-113` — researcher-
  readiness flags (currently only check file existence, not semantic content)

### Proposed invariant
New invariant concept — an "economic completeness" check for the artifact
suite:
```clojure
:evidence-includes-economic-context
;; For every dispute-related evidence record, the artifact must include:
;;   - bond required vs caller balance (for challenge actions)
;;   - resolver stake vs escrow value (for resolution actions)
;;   - victim claimable before/after (for settlement actions)
;;   - slash amount vs resolve profit (for slash actions)
```

### Suggested fix direction
Extend the evidence summary artifact to include economic context fields for
each evidence record. The `researcher-readiness` flags in the coverage report
should include an `economics-context?` flag that checks whether the evidence
summary contains these fields.

---

## Ranked Findings

| Rank | Finding | Priority | Status | Risk | Scenario |
|------|---------|----------|--------|------|----------|
| 1 | Challenge bond default (100) exceeds small escrow value | Q.A / Q.B | Confirmed | **High** — economic liveness failure | S-DR-080 |
| 2 | Slash succeeds but victim not made whole | Q.C | Confirmed | **High** — punishment without repair | S-DR-081 |
| 3 | Stake-to-escrow ratio unbounded (minimal stake, large escrow) | Q.F | Confirmed | **High** — profit >> max penalty | S-DR-082 |
| 4 | Escalation bond griefing (single address premium) | Q.B | Scenario gap | Medium — asymmetric cost | S-DR-083 |
| 5 | Evidence cannot prove economic vulnerability | Q.H | Observability gap | Medium — researcher blind | — |

## Files / Functions Inspected

| File | Function | Lines |
|------|----------|-------|
| `resolution.clj` | `challenge-resolution` | 524-615 |
| `resolution.clj` | `escalate-dispute` | 337-439 |
| `resolution.clj` | `execute-pending-settlement` | 453-483 |
| `resolution.clj` | `execute-fraud-slash` | 1005-1074 |
| `resolution.clj` | `finalize` | 1125-1138 |
| `lifecycle.clj` | `create-escrow` | 250-399 |
| `lifecycle.clj` | `finalize-escrow-accounting` | 223-226 |
| `payoffs.clj` | `calculate-challenge-bond-amount` | 41-55 |
| `payoffs.clj` | `calculate-prorata-slash-allocation` | 124-200 |
| `accounting.clj` | `post-appeal-bond` | 194-223 |
| `accounting.clj` | `record-claimable / record-claimable-v2` | 100-127 |
| `sew.clj` | `apply-action "execute-resolution"` | 243-258 |
| `sew.clj` | `apply-action "challenge-resolution"` | 295-305 |
| `state_machine.clj` | `transitions` (registry) | 161-198 |
| `authority.clj` | `authorized-resolver?` | 75-112 |
| `event_evidence.clj` | `capture-event-evidence!` | 247-307 |
| `summary.clj` | `build-evidence-summary` | 1-92 |
| `invariants.clj` | `check-all` | 1399-1474 |

## Existing Tests That Appear Related But Insufficient

| Test | What it tests | What it misses |
|------|---------------|----------------|
| S-DR-075 | Insufficient bond deterrence (resolver side) | **Challenger side** — bond vs escrow value |
| S-DR-044, 061, 063, 064 | Slash lifecycle (propose→execute→appeal) | **Post-slash repair** — victim's claimable balance |
| S-DR-001–004 | Basic lifecycle (happy path) | **Economics** — stake-to-escrow ratio |
| S24, S34–S37 | Adversarial slash/profit scenarios | **Unbounded** escrow-vs-stake gap |
| S-DR-050–056 | Module configuration edges | **Balance enforcement** — bond posting doesn't check caller |
| S-DR-071–074 | Governance manipulation | **Economic finality** — settlement before slash resolution |
| Calibration tests | Breakeven formula | **Per-instance economics** — per-escrow bond vs value check |

## False Positives Ruled Out

| Pattern | Why ruled out |
|---------|---------------|
| Kleros small-panel capture | SEW has no multi-resolver voting; single resolver per dispute per level. Resolver selection is deterministic (custom-resolver → module → snapshot). No panel to capture. |
| Kleros appeal-cost griefing via multiple rounds | SEW max-escalation-level = 2 (hard-coded). Only 3 rounds total. Griefing via repeated escalation is bounded. |
| UMA oracle assertion accepted with no bond | SEW challenge-resolution always requires a bond (default minimum 100). Bond cannot be zero by construction. |
| Commit-reveal / hidden participation | SEW has no commit-reveal mechanism. Resolver decisions are immediate and visible. No timing games possible. |
| Forced timeout via `automate_timed_actions` | The keeper path is an open action — anyone can call `automate_timed_actions`. Not a blocked path. |

## Recommended Next Implementation Step

Create three new S-DR theory-falsification scenarios (S-DR-080, 081, 082)
for Findings 1, 2, and 3. Each scenario must:

1. Declare `:purpose :theory-falsification` and `:expected-fail? true`
2. Include initial balances for all actors (where relevant)
3. Include explicit post-conditions in the scenario-notes
4. Produce trace artifacts that a researcher can inspect
5. Trigger at least one new invariant check (even if the invariant is
   currently unimplemented, document the expected failure)

After the scenarios exist, the protocol design questions (should the bond
default be higher? should slash compensation include victim repair?) can be
debated with concrete evidence.
