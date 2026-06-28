Consensus inputs must normalize to the Phase 1 submission shape.

Transport-specific code must stop before compute_agreement.

Same runner + same result hash = idempotent duplicate.

Same runner + different result hash = equivocation.

Same public key + different signed result hashes for same run request = cryptographic equivocation.

Consensus certificates must cite participant message hashes and agreed stable result hash.

Disagreements are evidence, not exceptional crashes.
