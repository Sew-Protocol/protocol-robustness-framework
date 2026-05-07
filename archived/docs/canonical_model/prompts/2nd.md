First: what you actually have (this is stronger than it looks)

You don’t just have a simulation—you have three overlapping systems:

1. Protocol execution layer

contract_model/*

This is:

a deterministic, replayable, Solidity-aligned state machine

2. Economic / adversarial model

model/*, adversaries/*, oracle/*

This is:

a stochastic, agent-based economic system

3. Scenario / experimentation layer

sim/*

This is:

orchestration + Monte Carlo + research exploration

👉 The problem isn’t lack of structure—it’s that:

the canonical model is currently split across (1) and (2), with (3) tightly coupled to both

The key issue (this is the root of future pain)

Right now:

contract_model defines what can happen
model defines why it happens (incentives, probabilities)
sim mixes:
orchestration
experiments
sometimes protocol assumptions

This leads to:

1. Split canonical model

There is no single place where:

“the protocol + economics = full game”

lives.

2. Hidden coupling

Example risks:

resolution.clj encodes assumptions that must match escalation_economics.clj
detection.clj affects incentives but is not tied to transitions
sim/* may embed logic that should live in the model
3. Scenario-driven drift

Because sim/* grew first:

logic leaks into scenarios
scenarios become semi-canonical
The move: make the executable model explicitly layered

You want to define a single canonical executable system, but structured.

Proposed architecture (tailored to your repo)
Layer 1 — Protocol kernel (deterministic)

Keep and slightly refine:

contract_model/

This becomes:

The canonical state transition system

Responsibilities:

state machine (state_machine.clj) ✅
lifecycle (lifecycle.clj) ✅
resolution (resolution.clj) ✅
accounting (accounting.clj) ✅
Required change

👉 Make this purely deterministic and economics-agnostic

No:

probabilities
incentives
agent reasoning

Only:

valid transitions
state updates
Layer 2 — Economic engine (this becomes critical)

Refactor from:

model/

Into something like:

economics/
  payoffs.clj
  incentives.clj
  detection.clj
  bribery.clj
  escalation.clj
Key rule

This layer must answer:

Given a state + action → what is the economic consequence?

NOT:

mutate protocol state
define transitions
Example separation

Instead of:

resolution.clj deciding payouts implicitly

Do:

resolution.clj → state transition only
economics/payoffs.clj → compute rewards/penalties
Layer 3 — Agent / strategy layer

Keep:

adversaries/
model/* (some parts)

But clarify:

This layer decides what actions agents take, not what happens when they do

Layer 4 — Simulation engine (this is where restructure is needed)

Right now:

sim/*

is:

phases
experiments
orchestration
sometimes logic
Refactor into:
simulation/
  engine.clj        ;; core loop
  scenarios.clj     ;; declarative scenario definitions
  experiments.clj   ;; sweeps, monte carlo
Critical rule

Simulation should:

call protocol transitions
call economic functions
run agents

But never define rules itself

The canonical executable model (final definition)

After restructuring:

Canonical model = Protocol kernel + Economic engine

Everything else:

agents
scenarios
monte carlo

is exploration of that model

Why this matters (non-obvious but crucial)

Right now, your “truth” is:

“whatever combination of files produces results”

After restructuring, it becomes:

“a composable system with clear boundaries”

This enables:

1. Faster iteration
change incentives → only economics layer changes
change rules → only protocol layer changes
2. Reduced rework
no more duplicating logic across sim + model + contract_model
3. Clear contract mapping

Solidity maps to:

protocol kernel only

Not:

economic assumptions
Where Allium now fits cleanly

Given this structure:

Allium should describe:

Layer 1 (protocol transitions)
Key invariants across layers

NOT:

economic calculations
agent strategies
High-impact improvements specific to your code
1. Extract “transition registry”

Right now transitions are:

functions

Add:

(def transitions
  {:to-disputed {...}
   :to-released {...}})

This enables:

introspection
validation
test generation
2. Unify detection + incentives

Currently:

oracle/detection.clj
model/economics.clj
model/escalation_economics.clj

These likely interact but are separate.

👉 Bring under one abstraction:

(defn expected-payoff [state action actor] ...)
3. Collapse phase files into parameterized scenarios

Your sim/phase_* structure is a smell:

it encodes experiments as code
not reusable

Instead:

{:scenario :phase-p
 :params {...}
 :agents {...}}
Biggest structural risk if you don’t change this

As complexity grows:

economic assumptions drift from transitions
scenarios encode hidden rules
contract alignment weakens

You’ll eventually hit:

“simulation says safe, contracts behave differently”

Final stance

You should absolutely:

Make the executable model canonical

But define it precisely as:

Protocol kernel (deterministic) + Economic engine (stochastic)

And enforce:

simulation = consumer, not author
contracts = constrained implementation of protocol kernel
