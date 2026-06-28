# Concepts — Stakeholder-Facing Explanation Layer

Concepts map protocol-level mechanics to stakeholder-facing vocabulary.
They help answer: *what does this protocol outcome mean for the people
involved?*

## Scope

Concepts are a **stakeholder-facing explanation layer only**. They exist
solely to make protocol outputs interpretable by non-expert audiences.

**Concepts must never affect:**
- Protocol execution (scenario running, dispute resolution, settlement)
- Evidence capture (trace collection, hashing, attestation)
- Canonical hashing or bundle root computation
- Protocol validity, correctness, or invariant checks
- Benchmark results, scoring, or claim evaluation
- Any registry or pipeline that feeds into the above

Concept enrichment is added **after** all protocol computation and
hashing are complete. It is a cosmetic overlay on reports.

## Concept Types

| Type | Layer | Directory | Description |
|------|-------|-----------|-------------|
| `:use-case` | `:stakeholder` | `use-case/` | Real-world scenarios (ecommerce, deposits, accounts) |
| `:decision-quality` | `:stakeholder` | `decision-quality/` | How the system decides contested outcomes |
| `:assurance` | `:framework-explanation` | `assurance/` | How to verify protocol correctness independently |

## What "Consensus" Means Here

In this project "consensus" refers to **contested-outcome confidence** or
**decision quality** — the set of properties that determine whether a
disputed outcome can be trusted. It does *not* refer to blockchain
validator consensus (proof-of-stake, BFT, fork choice, etc.).

The six decision-quality concepts answer: who decided (authority), what they
knew (evidence), when it became binding (finality), how to challenge it
(escalation), whether progress was made (liveness), and why the outcome
should be accepted (legitimacy).

## File Layout

```
data/concepts/
├── README.md
├── registry.edn                          ← index of all concepts
├── use-case/
│   ├── ecommerce.edn                     ← marketplace purchase
│   ├── event_deposits.edn                ← conditional deposits
│   ├── fixed_price.edn                   ← fixed-price listing
│   └── spending_accounts.edn             ← controlled balance
├── decision-quality/
│   ├── authority.edn                     ← who decides
│   ├── evidence.edn                      ← what facts available
│   ├── finality.edn                      ← when binding
│   ├── escalation.edn                    ← how to challenge
│   ├── liveness.edn                      ← timeliness of process
│   └── legitimacy.edn                    ← why accept outcome
└── assurance/
    └── verifiable_assurance.edn          ← forensic confidence
```

## File Structure

Each concept file is a single EDN map with these keys:

| Key | Purpose |
|-----|---------|
| `:concept/id` | Unique keyword identifier (matches registry) |
| `:concept/type` | `:use-case`, `:decision-quality`, or `:assurance` |
| `:concept/layer` | `:stakeholder` or `:framework-explanation` |
| `:concept/name` | Human-readable name |
| `:concept/summary` | One-paragraph explanation |
| `:concept/stakeholder-question` | The question this concept answers |
| `:concept/protocols` | Set of applicable protocol keywords |
| `:concept/roles` | Map of role keywords to role maps |
| `:concept/entities` | Map of entity keywords to entity maps |
| `:concept/actions` | Map of action keywords to action maps |
| `:concept/outcomes` | Map of outcome keywords to outcome maps |
| `:concept/failure-modes` | Vector of failure mode maps |
| `:concept/assumptions` | Vector of assumption strings |
| `:concept/out-of-scope` | Vector of out-of-scope statements |

Each role, entity, action, and outcome map may contain:

| Key | Purpose |
|-----|---------|
| `:maps-to` | Vector of `:protocol.*` keyword mappings |
| `:mapping/confidence` | `:approximate` if mapping is not 1:1 |
| `:mapping/note` | Explanation of an approximate mapping |
| `:description` | Human-readable description |

## Mapping Vocabulary

Concept layer `:maps-to` values use a consistent keyword taxonomy:

| Namespace | Used In | Example |
|-----------|---------|---------|
| `:protocol.actor/*` | `:concept/roles` | `:protocol.actor/sender`, `:protocol.actor/resolver` |
| `:protocol.role/*` | `:concept/roles` (assurance only) | `:protocol.role/verifier` |
| `:protocol.entity/*` | `:concept/entities` | `:protocol.entity/escrow`, `:protocol.entity/evidence` |
| `:protocol.action/*` | `:concept/actions` | `:protocol.action/create-escrow`, `:protocol.action/release` |
| `:protocol.outcome/*` | `:concept/outcomes` | `:protocol.outcome/released`, `:protocol.outcome/stuck` |

These keywords are ad-hoc stakeholder-facing labels, not internal protocol
type definitions. They bridge between concept language and protocol
mechanics without requiring exact correspondence to source types.

## Validation

```bash
bb concepts:validate
```

Checks that:
- Every EDN file under `data/concepts/` parses correctly
- Required concept keys are present
- Registry entries reference existing files
- File concept IDs match registry IDs
- Concept IDs are unique in the registry
- Protocols are known (`:protocol/sew-v1`, `:protocol/prf`)
- Files live in the subdirectory matching their `:concept/type`
- `:maps-to` values use keyword (not string) form with recognized namespaces
