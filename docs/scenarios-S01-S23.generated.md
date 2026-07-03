| S01 | baseline-happy-path | Create escrow → release; no disputes |
| S02 | dr3-dispute-release | Dispute opened; resolver releases to seller |
| S03 | dr3-dispute-refund | Dispute opened; resolver refunds buyer |
| S04 | dispute-timeout-autocancel | Dispute expires without resolution; auto-cancel |
| S05 | pending-settlement-execute | Pending settlement window; honest execution after deadline |
| S06 | mutual-cancel | Both parties agree to cancel |
| S07 | unauthorized-resolver-rejected | Non-authorized resolver call is rejected |
| S08 | state-machine-attack-gauntlet | Invalid state transitions attempted; all correctly rejected |
| S09 | multi-escrow-solvency | Multiple concurrent escrows; solvency maintained |
| S10 | double-finalize-rejected | Attempt to finalize an already-finalized escrow is rejected |
| S11 | zero-fee-edge-case | Escrow with fee_bps=0; correct handling |
| S12 | governance-snapshot-isolation (s12a+s12b) | Fee-param change after escrow A created does not apply retroactively to A / Escrow B created after fee change uses new params; A unchanged |
| S13 | pending-settlement-refund | Pending settlement resolved as refund |
| S14 | dr3-module-authorized | DR3 module resolves with correct authority |
| S15 | dr3-module-unauthorized-rejected | DR3 module with wrong authority is rejected |
| S16 | ieo-create-release | IEO escrow: create and release without dispute |
| S17 | ieo-dispute-no-resolver-timeout | IEO dispute with no resolver; timeout resolution |
| S18 | dr3-kleros-l0-resolves | Kleros L0 resolver resolves dispute at level 0 (zero appeal window) |
| S19 | dr3-kleros-escalation-rejected-l0-resolves | Preemptive escalation rejected (no pending settlement); L0 resolves; L1 blocked on terminal escrow |
| S20 | dr3-kleros-max-escalation-guard | Repeated preemptive escalations rejected; wrong-tier resolver rejected; dispute may stay open |
| S21 | dr3-kleros-pending-cleared-on-escalation | L0 resolves to pending; buyer escalates (clears pending); L1 resolves; keeper executes settlement |
| S22 | status-leak-agree-cancel-over-dispute | Regression: agree-to-cancel status cleared when dispute is raised |
| S23 | preemptive-escalation-blocked | Seller preemptive escalation rejected; L0 resolves; post-terminal escalation rejected |
