# Cancellation Game-Theory Gap Checklist

## Purpose

This checklist defines the work needed to upgrade cancellation analysis from
single-trace proxy checks to stronger game-theoretic evidence.

Current status in this repo:
- Protocol correctness and adversarial ordering regressions are strong.
- Equilibrium evidence is mostly proxy-level for cancellation-specific behavior.

## Goal

Establish high-confidence evidence that cancellation paths do not create
profitable strategic deviations (within configured parameter ranges).

## Checklist

- [ ] Define cancellation-specific hypotheses (explicit claims)
  - Honest mutual cancel dominates frivolous dispute in low-ambiguity cases.
  - Strategic delay-to-timeout is not economically dominant over honest settlement.
  - Unilateral cancellation policies (when enabled) do not introduce extortion equilibria.

- [ ] Build a cancellation strategy-profile matrix
  - Actors: buyer, seller, resolver.
  - Strategies: cancel-now, dispute-now, delay, escalate, no-op.
  - Include expected payoff vectors and assumptions.

- [ ] Add counterfactual deviation bundles
  - For each baseline cancellation trace, generate single-agent deviations:
    - `recipient_cancel -> raise_dispute`
    - `sender_cancel -> delay`
    - same-timestamp ordering permutations near boundaries.
  - Record whether deviation is profitable.

- [ ] Enforce multi-trace evidence for cancellation claims
  - Introduce bundle minimums: baseline + deviations + stress seeds.
  - Mark claim inconclusive when bundle minimum is not met.

- [ ] Add cancellation-specific parameter sweeps
  - Sweep timeout/appeal windows, fee levels, and cancellation strategy toggles.
  - Output regions where strategic preference flips.

- [ ] Emit cancellation-focused metrics
  - cancel→dispute conversion rate
  - timeout-induced cancellation rate
  - payout delta (cancel path vs dispute path)
  - profitable-deviation incidence by actor role.

- [ ] Add evidence-strength labeling in reports
  - `single-trace-proxy`
  - `multi-trace-deviation-tested`
  - `multi-epoch-population-proxy`

- [ ] Create a dedicated fixture suite
  - Proposed ID: `:suites/cancellation-equilibrium-validation`
  - Seed with canonical scenarios:
    - `S06_mutual-cancel`
    - `S22_status-leak-agree-cancel-over-dispute`
    - timeout cancellation scenarios (`S04`, `S17`).

- [ ] Define shipping gate criteria
  - No profitable unilateral cancellation deviation in >=95% tested seeds.
  - Zero invariant violations across cancellation bundle scenarios.
  - No extortion-positive equilibrium in production parameter band.

## Suggested Execution Order

1. Claims and strategy matrix
2. Counterfactual deviation generation
3. Suite + metrics instrumentation
4. Parameter sweep and thresholding
5. Evidence-strength reporting integration

## Notes

- This checklist is intentionally cancellation-specific.
- It complements (does not replace) existing general equilibrium and mechanism
  property checks.
