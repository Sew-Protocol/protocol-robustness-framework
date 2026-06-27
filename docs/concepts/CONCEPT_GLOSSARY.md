# Concept Glossary

Common terms used across concept definitions and their protocol mappings.

## Verifiable Assurance Concepts

A meta-concept that describes *how* stakeholders verify protocol outcomes
rather than *what* happened. Maps to the evidence-chain, canonical hashing,
attestation, and replay infrastructure.

| Concept Term | Protocol Mapping | Definition |
|-------------|-----------------|------------|
| Observer | `:protocol.role/verifier` | Party examining the evidence trail. |
| Auditor | `:protocol.role/verifier` | Third party performing independent verification. |
| Evidence Bundle | `:protocol.entity/evidence-bundle` | Portable collection of all evidence from a run. |
| Evidence Chain | `:protocol.entity/evidence-chain` | Linked evidence nodes with hash integrity. |
| Canonical Hash | `:protocol.entity/hash` | Deterministic SHA-256 over canonically serialized EDN. |
| Attestation | `:protocol.entity/attestation` | Cryptographically signed statement linking identity to a hash. |
| Trace Log | `:protocol.entity/trace` | Ordered sequence of protocol events. |
| Invariant Result | `:protocol.entity/invariant-result` | Pass/fail for a specific invariant check. |

## Role Terms

| Concept Term | Protocol Mapping | Definition |
|-------------|-----------------|------------|
| Buyer | `:protocol.actor/sender` | Party paying for goods or services. |
| Merchant | `:protocol.actor/recipient` | Party expected to fulfill the order or provide the service. |
| Depositor | `:protocol.actor/sender` | Party committing funds to a deposit or account. |
| Beneficiary | `:protocol.actor/recipient` | Party entitled to claim released funds. |
| Resolver | `:protocol.actor/resolver` | Party or module that decides disputes. |
| Governance | `:protocol.actor/governance` | Protocol governance that adjusts rules or intervenes. |
| Condition Authority | `:protocol.actor/resolver` / `:protocol.actor/oracle` | Party that attests whether conditions are satisfied. |
| Spending Authority | `:protocol.actor/resolver` | Party that authorises individual spends. |
| Account Holder | `:protocol.actor/sender` / `:protocol.actor/recipient` | User who owns a spending account. |

## Entity Terms

| Concept Term | Protocol Mapping | Definition |
|-------------|-----------------|------------|
| Order | `:protocol.entity/escrow` | Commercial transaction being protected. |
| Payment | `:protocol.entity/funds` | Funds held pending release, refund, or dispute. |
| Deposit | `:protocol.entity/escrow` / `:protocol.entity/funds` | Funds committed with a release condition. |
| Dispute | `:protocol.entity/dispute` | A challenge or disagreement about entitlement. |
| Evidence | `:protocol.entity/evidence` | Facts submitted to support a claim. |
| Condition | `:protocol.entity/condition` | Predicate that must be satisfied for release. |
| Account | `:protocol.entity/escrow-aggregate` | Balance abstraction over multiple escrows. |
| Hold | `:protocol.entity/pending-release` | Pending charge against a balance. |
| Withdrawal | `:protocol.entity/funds` | Funds moved out of an account. |

## Outcome Terms

| Concept Term | Protocol Mapping | Definition |
|-------------|-----------------|------------|
| Merchant Paid | `:protocol.outcome/released` | Merchant receives payment. |
| Buyer Refunded | `:protocol.outcome/refunded` | Buyer receives funds back. |
| Funds Locked | `:protocol.outcome/stuck` | Neither party can access funds. |
| Manipulated Resolution | `:protocol.outcome/adversarial-resolution` | Unfair outcome via authorized path. |
| Slash Applied | `:protocol.outcome/slashed` | Party loses bond due to rule violation. |

## Failure Mode Terms

| Failure Mode | Description |
|-------------|-------------|
| Merchant-controlled resolver | Resolver aligned with merchant rules unfairly. |
| Buyer griefing | Repeated low-merit disputes to delay payout. |
| Fund lock | No viable resolution path before timeout. |
| False attestation | Condition attested incorrectly. |
| Over-draw | Withdrawal exceeding available balance. |
| Hold never released | Indefinite hold reducing available balance. |
| Unauthorized freeze | Account frozen without proper authority. |

## Metric Terms

| Metric | Source | Definition |
|--------|--------|------------|
| orders-placed | Trace | Number of escrows created. |
| orders-disputed | Trace | Number of flows entering dispute. |
| funds-locked | Protocol metrics | Funds unavailable at terminal state. |
| holds-active | Trace | Number of currently reserved holds. |
| balance | Trace | Current available account balance. |
