## Mini glossary (shared)

Scope note: This document is **SPE-wide** (conceptual/mechanism-level); for **counterfactual engine implementation details and evaluator output contracts**, start with `docs/subgame-counterfactual-improvements.md`.

- **SPE proxy**: Bounded, replay-based subgame-perfect-equilibrium evidence; not a formal full-game proof.
- **Decision node**: A strategic action point evaluated for deviation profitability.
- **Proper subgame node**: Node where relevant state/history is publicly checkable for SPE-style evaluation.
- **Information-set node**: Node where private/hidden information affects action availability or payoff inference.
- **Continuation policy**: Rule for downstream behavior after a deviation (e.g., `:trace-following`, `:policy-response`).
- **Utility spec**: Declared payoff model used for comparisons (e.g., terminal-realized or reputation-aware utility).
- **Local regret**: `best-alt-utility - chosen-utility` at a node.
- **Bundle regret**: Bounded multi-step approximation built from local regret.
- **Epsilon thresholds**: Materiality tolerances (`:epsilon-abs`, `:epsilon-rel`) for practical pass/fail semantics.
- **Counterexample**: Structured profitable-deviation evidence record.
- **Off-path coverage**: How many generated/evaluated/inconclusive nodes were explored beyond the realized path.
- **Proof sketch**: Human-readable Claim/Method/Checked/Result summary emitted with SPE observed output.

Essential additions for credible Subgame Perfect Equilibrium checking
1. Explicit game tree / subgame representation

To make any SPE claim, the system needs a clear representation of the strategic structure being checked.

At minimum, each subgame should define:

decision node ID
acting agent
protocol state snapshot
available actions
chosen action
successor state or replay transition
terminal / continuation utility basis
parent-child relationship between nodes
whether the node starts a proper subgame

Why it matters:

Without this, the checker is really doing “local counterfactual replay,” not SPE validation. SPE requires reasoning over every relevant subgame, not just isolated decisions.

For Phase 4, this does not need to become a full extensive-form solver. But the output should start looking like:

{:subgame/id "escrow-42:buyer:challenge-window"
 :root-node "node-17"
 :acting-agent :buyer
 :pre-state-hash "..."
 :available-actions [:challenge :do-nothing :settle]
 :chosen-action :challenge
 :children [...]
 :utility-model :escrow-terminal-v1}
2. Continuation strategy semantics

This is probably the most important SPE-specific requirement.

SPE is not just “no one regrets this action locally.” It is:

At every subgame, the strategy from that point onward is optimal, given the continuation strategies of everyone else.

So the checker needs an explicit answer to:

After this node, what strategy profile is assumed?

Possible continuation modes:

Trace continuation: follow the original trace where still valid.
Policy continuation: regenerate downstream actions from declared agent policies.
Best-response continuation: compute bounded best responses after deviation.
Hybrid continuation: replay fixed exogenous events, regenerate strategic actions.

Why it matters:

If an alternative action is tested but downstream behavior is vague, the regret number is not an SPE result. It is only a replay artifact.

Essential addition:

{:continuation/mode :trace-if-valid
 :invalid-action-policy :skip-or-regenerate
 :strategic-response-policy :declared-agent-policy
 :exogenous-event-policy :freeze}
3. Strategy profile definition

SPE is a property of a strategy profile, not just a trace.

A trace shows what happened. A strategy says what each agent would do at each possible decision point, including points that were not reached.

Essential addition:

Define a minimal candidate strategy profile:

{:strategy-profile/id "honest-resolution-v1"
 :buyer-policy :policy/buyer-rational-v1
 :seller-policy :policy/seller-rational-v1
 :resolver-policy :policy/resolver-honest-v1
 :governance-policy :policy/governance-no-intervention-v1}

Why it matters:

A trace can be consistent with many strategies. SPE requires checking whether the strategy remains optimal in subgames, including counterfactual branches.

For v1, this can be bounded and partial:

only define strategies for known node types,
mark unknown node types as inconclusive,
avoid pretending full-game SPE has been proven.
4. Proper subgame boundary rules

Not every decision node cleanly starts a subgame. In games with imperfect information, hidden state, or unresolved private knowledge, some nodes may not define proper subgames.

Essential addition:

Classify nodes as:

proper subgame root
local decision node only
information-set node
not SPE-checkable
requires sequential-equilibrium-style treatment

Why it matters:

Subgame perfection assumes the subgame begins at a node where all relevant history is commonly known. If agents do not share the same information, SPE may be the wrong concept, or at least incomplete.

For Sew, many on-chain states are public, which helps. But off-chain facts, evidence quality, resolver beliefs, and buyer/seller private incentives may not be fully public.

So the system should be honest:

{:node/id "resolver-verdict-3"
 :spe/checkability :proper-subgame
 :reason "all protocol state public; verdict actions available from public dispute state"}

or:

{:node/id "buyer-evidence-choice-2"
 :spe/checkability :information-set-node
 :reason "buyer private evidence quality not modeled"}
5. Utility model with continuation value

A credible SPE checker needs a utility function that can compare actions over the relevant horizon.

For Sew, utility may include:

escrow payout
bond loss
fees
appeal costs
delay cost
gas assumptions
slashing
resolver reputation
future resolver eligibility
expected future earnings
governance or protocol-level effects, if modeled

Essential addition:

{:utility/model :resolver-dispute-utility-v1
 :includes [:payout :bond-loss :fees :delay-cost :reputation-delta]
 :discount-rate 0.0
 :terminal-mode :trace-end}

Why it matters:

If the resolver loses 1 unit now but gains future selection probability, a single-trace payout utility may produce the wrong SPE conclusion.

For v1, it is fine to use a simplified utility model, but it must be explicit.

6. Backward-induction or bounded backward-induction support

For finite subgames, SPE is usually checked by backward induction: solve later decisions first, then earlier decisions.

Essential addition:

For bounded subgames, evaluate from terminal states backward:

enumerate terminal utilities,
solve final decision nodes,
propagate values upward,
compare chosen actions to optimal actions at each node.

Why it matters:

Local regret computed forward from a trace is useful, but SPE is inherently recursive. Earlier actions depend on the value of later subgames.

A lightweight v1 version could be:

{:evaluation/mode :bounded-backward-induction
 :max-depth 3
 :terminal-utility :trace-or-cutoff
 :cutoff-policy :estimate-from-current-utility}

This would be a major credibility improvement.

7. Off-path action handling

SPE requires strategies to be credible even after histories that do not occur on the main path.

This is one of the core distinctions between Nash equilibrium and subgame perfect equilibrium.

Essential addition:

For each reachable counterfactual branch, define what agents would do next.

Examples:

If buyer does not challenge, what does seller do?
If seller escalates unexpectedly, what does buyer do?
If resolver gives a malicious verdict, who appeals?
If appeal bond is high, does the harmed party still escalate?

Why it matters:

A trace-only checker mostly validates on-path behavior. SPE needs at least bounded off-path credibility.

The system should report:

{:off-path-coverage
 {:nodes-generated 24
  :nodes-evaluated 19
  :nodes-inconclusive 5
  :max-depth 3}}
8. Tie and epsilon-equilibrium semantics

Exact SPE can be too brittle in practical simulations.

Essential addition:

Support:

exact SPE
epsilon-SPE
weak preference / tie
indifference classes
materially profitable deviation threshold

Example:

{:spe/threshold
 {:epsilon-absolute 1.00
  :epsilon-relative 0.005
  :currency :usd-equivalent
  :tie-policy :chosen-action-valid-if-within-epsilon}}

Why it matters:

If the chosen action returns 100.00 and the best alternative returns 100.01, calling that an SPE failure may be technically precise but practically useless.

For security work, the key question is often:

Is there a materially profitable deviation?

9. Result classifications beyond pass/fail

A credible SPE evaluator should not force all results into binary outputs.

Essential result types:

:spe/pass
:spe/epsilon-pass
:spe/fail-profitable-deviation
:spe/inconclusive-missing-actions
:spe/inconclusive-missing-utility
:spe/inconclusive-continuation-undefined
:spe/not-a-proper-subgame
:spe/out-of-scope-depth-limit

Why it matters:

This protects the credibility of the tool. It makes the system honest about what it did and did not validate.

A strong output might say:

{:result :spe/epsilon-pass
 :proper-subgames-checked 17
 :local-decision-nodes-checked 9
 :inconclusive-nodes 3
 :max-regret 0.42
 :epsilon 1.00}

That is much more defensible than a broad “SPE verified” label.

10. Counterexample traces for failures

When SPE fails, the checker should emit a concrete counterexample.

Essential addition:

For every profitable deviation:

original path
deviating node
chosen action
better alternative
utility delta
replayed counterfactual branch
final state difference
reason the alternative was valid

Why it matters:

This turns SPE checking into an attack-discovery tool.

Example:

{:failure/type :profitable-deviation
 :node/id "resolver-verdict-7"
 :agent :resolver
 :chosen-action :honest-verdict
 :better-action :seller-favoring-verdict
 :chosen-utility 100
 :alternative-utility 145
 :regret 45
 :counterfactual-trace-ref "results/counterfactuals/trace-abc.edn"}

This is essential if the output is going to be useful to protocol engineers.

Very useful improvements
11. Imperfect-information extension path

SPE is cleanest in perfect-information extensive-form games. Real dispute resolution often has imperfect information.

Very useful addition:

Add a pathway toward:

information sets,
beliefs,
sequential equilibrium,
perfect Bayesian equilibrium,
or at least “SPE over public on-chain state only.”

Why it matters:

Disputes often depend on evidence, intent, delivery quality, fraud probability, and subjective beliefs. These are not always fully visible on-chain.

The evaluator should distinguish:

{:equilibrium-concept :public-state-spe-proxy}

from:

{:equilibrium-concept :full-information-spe}

This avoids overclaiming.

12. Agent belief and evidence modeling

For dispute protocols, resolver and participant decisions often depend on beliefs.

Very useful addition:

Model things like:

probability buyer is honest,
probability seller delivered,
likelihood of appeal,
probability Kleros reverses,
expected slashing risk,
expected reputation loss.

Why it matters:

A resolver’s optimal action depends heavily on expected appeal/reversal/slashing, not just immediate payout.

This is especially relevant to the malicious-verdict survival model you have been discussing.

13. Multiple equilibrium candidate comparison

There may be more than one plausible strategy profile.

Very useful addition:

Compare candidate equilibria:

honest equilibrium
lazy/no-challenge equilibrium
extortion equilibrium
collusive resolver equilibrium
high-appeal equilibrium
low-appeal equilibrium

Why it matters:

Showing that the intended equilibrium is stable is good. Showing that bad equilibria are unstable, fragile, or less attractive is even better.

Output could include:

{:candidate-equilibria
 [{:id :honest-resolution :status :epsilon-pass}
  {:id :resolver-collusion :status :fail :reason :appeal-slashing-negative-ev}
  {:id :buyer-extortion :status :fail :reason :seller-challenge-profitable}]}

This would be very powerful for mechanism-design credibility.

14. Equilibrium basin / parameter sensitivity

SPE may hold under one parameter set and fail under another.

Very useful addition:

Run SPE-proxy checks across parameter sweeps:

appeal bond size
resolver bond size
dispute fee
escalation fee
probability of appeal
probability of reversal
yield opportunity cost
timeout length
resolver reputation penalty
Kleros correctness assumption

Why it matters:

The important question is not only:

Does the equilibrium hold?

It is:

Under what parameter ranges does the desired equilibrium remain stable?

This gives you governance-relevant outputs.

15. Minimal profitable deviation search

Rather than only checking pre-enumerated alternatives, search for the smallest change that breaks SPE.

Very useful addition:

Ask:

What is the smallest bond reduction that makes malicious verdicts profitable?
What is the lowest appeal probability that breaks honest resolution?
What timeout length creates profitable griefing?
What fee level deters valid challenges?

Why it matters:

This turns the evaluator into a robustness-boundary finder.

That is much more useful than a binary pass/fail.

16. Equilibrium robustness score

For communication and dashboards, derive a compact score from the SPE evidence.

Possible metrics:

max regret
number of profitable deviations
severity-weighted regret
percentage of proper subgames checked
off-path coverage depth
parameter sensitivity margin
worst-case agent class

Example:

{:spe-robustness
 {:score 0.87
  :max-regret 0.42
  :checked-subgames 31
  :coverage 0.78
  :worst-agent :resolver
  :weakest-parameter :appeal-probability}}

Why it matters:

This makes the output easier to compare across scenarios and releases.

The score should never replace the detailed evidence, but it is useful for tracking.

17. Integration with CDRS theory claims

This is especially high value for your architecture.

Very useful addition:

Allow a CDRS theory block to declare the equilibrium concept being tested.

Example:

{:theory
 {:claim-id :honest-resolution-is-stable
  :equilibrium-concept :bounded-public-state-epsilon-spe
  :assumptions
  {:appeal-prob-wrong 0.4
   :l1-reversal-prob 0.85
   :l2-reversal-prob 0.95}
  :falsifies-if
  [{:metric :max-regret :op :> :value 1.0}
   {:metric :profitable-deviation-count :op :> :value 0}]}}

Why it matters:

It connects simulation outputs directly to falsifiable mechanism-design claims.

This is probably one of the most valuable Sew-specific upgrades.

18. Human-readable proof sketch output

For reviewers, the system should explain why it believes the candidate is SPE-like.

Implementation-aligned guidance (current output):

The proof sketch should include a **Method** section and a **Result** section,
with fields aligned to emitted keys from `scenario/equilibrium.clj`:

- Method
  - continuation policy mode/version
  - utility spec type/version
  - max deviation depth
  - epsilon absolute/relative thresholds
  - memoization diagnostics (enabled/disabled, entries, hits)

- Checked
  - proper subgame node count
  - information-set node count
  - not-checkable node count

- Result
  - pass/fail/inconclusive summary
  - max regret and threshold context
  - rich SPE result vocabulary key (e.g. `:spe/pass`, `:spe/epsilon-pass`)
  - counterexample count when failing

Example sketch shape:

Claim: Bounded public-state SPE proxy under declared strategy profile honest-resolution-v1.

Method:
- continuation-policy: :trace-following (version v1)
- utility-spec: :terminal-realized-v1 (version v1)
- max deviation depth: 2
- epsilon: abs=1.0, rel=0.01

Checked:
- 14 proper subgame node(s)
- 4 information-set node(s) (inconclusive)
- 2 not-checkable node(s)
- memoization: enabled, entries=14, hits=3

Result:
- No profitable deviation exceeded epsilon = 1.0
- Max regret: 0
- SPE result: :spe/pass

Why it matters:

This bridges raw simulation output and auditor/mechanism-review comprehension,
while making assumptions explicit and reducing over-claim risk.

19. Solver/tool integration path

Longer term, it may be useful to export bounded subgames into formats consumable by game-solving tools.

Very useful addition:

Export to:

Gambit-style extensive-form game representation,
custom EDN/JSON game tree,
normal-form approximations for small subgames,
or later theorem/proof tooling.

Why it matters:

It gives external reviewers a way to inspect or independently solve small subgames.

This is not essential for v1, but it increases credibility over time.

20. Regression suite for equilibrium claims

Once an equilibrium claim passes, it should become a regression target.

Very useful addition:

For each important claim:

fixture trace
parameter set
candidate strategy profile
expected max regret range
expected result classification
saved counterfactual table hash

Why it matters:

This prevents later protocol or simulator changes from silently weakening the mechanism.

Example:

{:suite/id :honest-resolution-spe-regression
 :expected
 {:result #{:spe/pass :spe/epsilon-pass}
  :max-regret [:<= 1.0]
  :profitable-deviation-count 0
  :deterministic-hash "abc123"}}

This is very high value for ongoing development.

Priority ranking
Essential for a defensible SPE-proxy
Explicit game tree / subgame representation
Continuation strategy semantics
Strategy profile definition
Proper subgame boundary rules
Utility model with continuation value
Bounded backward induction
Off-path action handling
Epsilon / tie semantics
Rich result classifications
Counterexample traces for failures
Very useful for making the checker powerful
Imperfect-information extension path
Agent belief and evidence modeling
Multiple equilibrium candidate comparison
Parameter sensitivity / robustness margins
Minimal profitable deviation search
Equilibrium robustness score
CDRS theory-claim integration
Human-readable proof sketch output
External solver export path
Equilibrium regression suite
The key distinction

The current Phase 4 counterfactual evaluator can support a useful claim like:

“No profitable one-step deviation was found at checked decision nodes under this replay policy.”

To move toward an SPE claim, the system needs to support something closer to:

“For each checked proper subgame, the candidate continuation strategy is optimal within the bounded action set, under the declared utility model and information assumptions.”

That wording is much stronger, but also much more precise. It avoids the trap of claiming full mathematical SPE when the system is really producing bounded, replay-based, evidence-bearing SPE-proxy results.

Suggested phrasing for the actual claim

I would avoid saying:

“The protocol is SPE-verified.”

Better:

“The simulator evaluates bounded public-state SPE proxies by replaying counterfactual subgames from deterministic pre-state snapshots, checking whether any available deviation exceeds a declared regret threshold under an explicit continuation strategy and utility model.”

For external communication:

“We test whether the intended dispute-resolution strategy remains stable across bounded counterfactual subgames, not just along the happy path.”