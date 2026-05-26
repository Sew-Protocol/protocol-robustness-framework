# Claim Registry (Generated)

Source of truth: `src/resolver_sim/definitions/registry.clj` (`claims`, `claim-scenario-map`).

Definitions hash: `1082033428`

| Claim ID | Title | Type | Evidence mode | Supporting scenarios | Falsifying scenarios | Related invariants |
|---|---|---|---|---|---|---|
| `appeal-window-enforced` | Appeal window enforces settlement timing | `time-safety` | `support` | `S32_forking-strategist-premature-settlement-rejected`, `S36_profit-maximizer-pre-window-execute-rejected`, `S74_appeal-deadline-boundary` | _none_ | `finality` |
| `fork-isolation` | Forking outcomes remain escrow-isolated | `safety` | `support` | `S33_forking-strategist-two-escrow-fork-isolation`, `S62_cross-token-isolation-under-dispute-load` | _none_ | `conservation`, `solvency` |
| `forking-l1-reversal` | L1 reversal can overturn L0 decision under valid escalation | `dispute-resolution` | `support` | `S26_forking-strategist-l1-reversal` | _none_ | `finality`, `solvency` |
| `forking-l2-path` | Escalation to L2 path remains valid and bounded | `dispute-resolution` | `support` | `S27_forking-strategist-l2-fork`, `S31_forking-strategist-all-levels-confirm` | _none_ | `finality`, `conservation` |
