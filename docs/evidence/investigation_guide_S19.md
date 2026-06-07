# Researcher Investigation Guide: Kleros Escalation (S19)

This guide outlines how to investigate the Kleros-level escalation behavior in scenario `S19_dr3-kleros-escalation-rejected-l0-resolves.json`.

## 1. Where to Find Findings
The authoritative trace is located at:
`results/evidence/kleros-preemptive-escalation-rejected-l0.result.json`

## 2. Interpreting Protocol Behavior
The trace is a structured JSON, not a plain-English log. To interpret the Kleros perspective, look for these keys:

- **`trace` (in the full JSON):** This contains the sequential list of protocol events. Look at each `trace-entry`.
- **`action`:** Shows the triggered protocol operation (e.g., `escalate_dispute`, `execute_resolution`).
- **`error` / `reject-class`:** These are the most critical fields. If a Kleros-level action is rejected, the `error` field contains the protocol-level reason (e.g., `:no-resolution-to-appeal` or `:transfer-not-in-dispute`).
- **`transition/id`:** Provides a high-level classification of what the protocol transition attempted to achieve.

## 3. Are there Plain-English Text explanations?
**No.** The raw `.result.json` files are highly structured, technical protocol-modeling artifacts. They are designed for machine validation, not human reading.

### Bridging the Gap
To translate these technical artifacts into plain English:
1.  **Use the Notebooks:** The project includes Clojure notebooks (e.g., `notebooks/dispute_resolution.clj`) that import these `.result.json` artifacts and project them into human-readable narratives or dashboards.
2.  **Inspect `trace-metadata`:** Within each `trace-entry`, the `trace-metadata` field often contains higher-level protocol semantic interpretations that are easier to map to English descriptions than the raw JSON fields.
3.  **Check the Registry:** The `claimable-classification.json` generated alongside this trace provides a protocol-specific semantic interpretation of the final state, which is often easier to interpret than the raw trace.

## 4. Verification Check
To verify what happened:
1.  Open `results/runs/<run-id>/test-artifacts.json`.
2.  Find the `scenario-result` entry to confirm the path to your trace.
3.  Load the trace into the `notebooks/dispute_resolution.clj` workbench to see the visualized resolution flow.
