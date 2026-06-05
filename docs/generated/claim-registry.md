# Claim Registry (Generated)

Source of truth: `src/resolver_sim/definitions/registry.clj` (`claims`, `claim-scenario-map`).

Definitions hash: `1730484672`

| Claim ID | Title | Type | Evidence mode | Supporting scenarios | Falsifying scenarios | Related invariants |
|---|---|---|---|---|---|---|
| `appeal-window-enforced` | Appeal window enforces settlement timing | `time-safety` | `support` | `S32_forking-strategist-premature-settlement-rejected`, `S36_profit-maximizer-pre-window-execute-rejected`, `S74_appeal-deadline-boundary` | _none_ | `finality` |
| `bribery-neutralized-by-l1` | L1 challenge reverses biased L0 ruling | `dispute-resolution` | `support` | `S42_resolver-buyer-bribery-loop` | _none_ | `finality`, `conservation` |
| `dr3-reversal-slash-disabled` | DR3 v3 disables non-zero reversal slashes | `safety` | `support` | `S41_dr3-reversal-slash-disabled` | _none_ | `reversal-slash-disabled` |
| `fork-isolation` | Forking outcomes remain escrow-isolated | `safety` | `support` | `S33_forking-strategist-two-escrow-fork-isolation`, `S62_cross-token-isolation-under-dispute-load`, `S62_cross-token-fee-on-transfer-under-dispute-load`, `S62_cross-token-parallel-appeal-depths-under-dispute-load` | _none_ | `conservation`, `solvency` |
| `forking-l1-reversal` | L1 reversal can overturn L0 decision under valid escalation | `dispute-resolution` | `support` | `S26_forking-strategist-l1-reversal` | _none_ | `finality`, `solvency` |
| `forking-l2-path` | Escalation to L2 path remains valid and bounded | `dispute-resolution` | `support` | `S27_forking-strategist-l2-fork`, `S31_forking-strategist-all-levels-confirm` | _none_ | `finality`, `conservation` |
| `resolver-capacity-enforced` | Resolver concurrent dispute capacity is enforced | `safety` | `support` | `S62_resolver-capacity-concurrent-dispute-load` | _none_ | `solvency` |
| `reversal-slash-track1` | Same-evidence reversal slash executes immediately | `safety` | `support` | `s101-reversal-slash-track1-enabled`, `S103_l2-reversal-slash-ids` | _none_ | `solvency`, `conservation` |
| `reversal-slash-track2-executes` | Rejected Track 2 reversal appeal allows slash execution | `safety` | `support` | `S107_reversal-track2-appeal-rejected-executes` | _none_ | `slash-status-consistent?`, `solvency` |
| `reversal-slash-track2-reversed` | Track 2 reversal slash can be reversed on appeal | `safety` | `support` | `S106_reversal-track2-evidence-appeal` | _none_ | `slash-status-consistent?`, `solvency` |
