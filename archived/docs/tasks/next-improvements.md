  1. High-Priority Infrastructure & Documentation
   * ✅ Architecture docs updated: docs/ now reflects DisputeProtocol kernel vs. SEW plugin split.
   * Suite Manifest: Create the data/fixtures/suites/manifest.edn as a single registry file, making it easy for users to list and discover all available suites.
   * Determinism Proof: Execute the "Double Run" check (run-suite twice, byte-compare golden/ reports) and document the result as a guarantee.

  2. Trace Minimisation (Refining Implementation)
   * Invariant-Aware Minimisation: Debug the replay engine timing to ensure the :time-lock-integrity invariant fires reliably during minimization (currently minimized as a
     "happy path" trace).
   * Greedy Multi-Step Pruning: Ensure the minimize function correctly prunes agent actors that are no longer referenced in the minimized event sequence.

  3. ✅ Abstraction (The "Protocol Kernel" Layer) — COMPLETE
   * ✅ DisputeProtocol interface extracted to protocols/protocol.clj (8 methods).
   * ✅ SEW implementation migrated from contract_model/ to protocols/sew/*.
   * ✅ DummyProtocol proof-of-concept implemented and tested against all 41 scenarios.
   * ✅ replay.clj is now genuinely protocol-agnostic (no protocols/sew/* imports).

  4. Canonical Vocabulary Expansion
   * ✅ Event Typing: :transition/type and :effect/type classifications implemented in
     protocols/sew/trace_metadata.clj and exposed via DisputeProtocol.classify-transition.
   * Outcome & Integrity Mapping: Fully integrate the Resolution Trace Metadata (:outcome, :integrity, :failure) into the run-suite reporter.

  5. Roadmap Coverage & Testing
   * Coverage Manifest: Populate the "Coverage Manifest" in data/fixtures/README.md with:
       - Covered: Escrow lifecycle, Baseline timeout, Escalation spam.
       - In Progress: Resolver withdrawal guard, Fee cap breaches.
       - Backlog: Collusion rings, Token pathologies (FoT), Cross-vault leakage.
   * Golden Snapshot Discipline: Formally define the "Reviewer-only" update rule for data/fixtures/golden/ files to prevent drift from being accidentally blessed.
