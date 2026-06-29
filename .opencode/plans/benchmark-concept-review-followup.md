# Plan: Outstanding Benchmark & Concept Items

**Date:** 2026-06-30
**Session scope:** Shortfall-allocation benchmark fixes (scenario IDs, global concepts, scoring invariants, golden fixture) — DONE

---

## Remaining Items (Prioritized)

### P1: Benchmark scenario ID validation

**Problem:** The validator checks that pack registries exist and that scenario suite keys resolve to registered suites, but does **not** validate that the individual `:scenario/id` values in a pack manifest's `:benchmark/scenarios` list actually exist in the referenced suite's path list (or manifest, when using a reference-validation suite). A typo or drift between manifest and suite silently passes validation and only surfaces at execution time.

**Evidence:** [benchmarks_validate.clj:68-76](scripts/benchmarks_validate.clj) checks suite-key resolution; scenario IDs are never cross-referenced against suite contents.

**Effort:** Small (~1h)

**Plan:**
1. Add a function `validate-scenario-ids!` in `benchmarks_validate.clj` that, for each benchmark with `:benchmark/scenarios`, looks up the suite's paths and checks that every `:scenario/id` matches either a manifest public ID or a file path stem in the suite.
2. Handle both manifest-backed suites (reference-validation-v1) and path-list suites (all Sew suites).

---

### P2: Sew benchmark claims wiring

**Problem:** All four Sew benchmarks declare claims (`:claim/no-unauthorized-release`, `:claim/funds-conserved`, `:claim/dispute-liveness`, `:claim/slashing-conservation`, etc.) but no evaluator exists for any of them. The Level 1 evaluator registry only has the 5 mechanical claims from the PRF benchmarks. Sew claims are Level 3 (semantic) with no implementation. The severity-weighted scoring rule references them with severities, but the benchmark report inevitably shows `:claim/status :declared-not-verified`.

**Evidence:** [severity-weighted-v1.edn:16-21](benchmarks/scoring/severity-weighted-v1.edn) lists claims with severities. [claims.clj](src/resolver_sim/benchmark/claims.clj) has no evaluators for sew-prefixed claims.

**Options:**
- **A (Conservative):** Add Level 1 mechanical evaluator stubs for each Sew claim that check for scenario outcome existence (same pattern as PRF's `:replay-result-present`). Reports advance from `:declared-not-verified` to `:verified` or `:partial`.
- **B (Ambitious):** Implement Level 2 invariant-backed evaluators for Sew claims using existing post-hoc invariants (e.g., `:claim/slashing-conservation` → check `:conservation-of-funds` & `:slash-distribution-consistent` invariants passed for all scenarios).
- **C (Honest):** Document in DESIGN_CLAIM_VERIFICATION.md that Sew claims are Level 3 and currently unevaluated. Add a validation warning that flags Sew benchmarks for missing evaluators.

**Effort:** A=2h, B=8h, C=1h

---

### P3: evidence-integrity-v1 cross-benchmark concept dependency

**Problem:** `evidence-integrity-v1.edn` references concept `:robustness/evidence-integrity`, which only exists as a benchmark-local concept in `benchmarks/concepts/protocol-robustness-v0.edn`. If protocol-robustness-v0's concept file is removed or renamed, evidence-integrity's concept reference breaks silently.

**Evidence:** [evidence-integrity-v1.edn:17](benchmarks/packs/prf-core/evidence-integrity-v1.edn) has `:benchmark/concepts [:robustness/evidence-integrity]`.

**Plan:**
1. Add `:robustness/evidence-integrity` to evidence-integrity-v1's own benchmark concept file, OR
2. Create a shared concept source that both packs can reference, OR
3. Add a validation check that a concept's source file can be resolved to exactly one location.

**Effort:** Small (~30m)

---

### P4: `:concept/metrics` key missing from 5 concept files

**Problem:** The `concept-registry` `load-registry` function logs warnings for `ecommerce`, `event-deposit`, `spending-account`, `fixed-price`, and `verifiable-assurance` — all missing the `:concept/metrics` key. The `concepts_validate.clj` validator doesn't enforce this key (it's not in `required-keys`).

**Evidence:** [concepts_validate.clj:10-14](scripts/concepts_validate.clj) defines `required-keys` which does not include `:concept/metrics`. The warnings appear in every benchmark run log.

**Plan:**
1. Add `:concept/metrics` to the 5 concept files with placeholder entries, or
2. Add `:concept/metrics` to `required-keys` in `concepts_validate.clj` and fix all files, or
3. Remove the warning from the registry loader if `:concept/metrics` is legitimately optional.

**Effort:** Small (~1h for full fix with option 2)

---

### P5: Benchmark concept schema validation

**Problem:** Concept files under `benchmarks/concepts/` have a different schema (`:concept/maps-to`, `:concept/stakeholder-language`, `:concept/why-it-matters`) but zero structural validation. The validator only checks file existence.

**Evidence:** [benchmarks_validate.clj:147-163](scripts/benchmarks_validate.clj) loads concept files and merges them into an index, but never validates their internal structure.

**Plan:**
1. Define required keys for benchmark concepts (different from global concept schema).
2. Add validation in `benchmarks_validate.clj` loop over concept files.
3. Warn if a dimension referenced in `:benchmark/scenarios` has no matching concept entry in the local concept file.

**Effort:** Medium (~2h)

---

### P6: Sew slash-obligation-allocation concept not surfaced

**Problem:** The `:pro-rata/slash-obligation-allocation` definition in Sew source (`protocols_src/resolver_sim/protocols/sew/`) has pro-rata allocation logic that is tested at the unit/test level but not referenced from the benchmark concept layer. This means there's no stakeholder-facing explanation for how slash obligations are allocated pro-rata.

**Plan:**
1. Add `data/concepts/allocation/slash_obligation.edn` — mirroring the partial-fill/shortfall pattern.
2. Register in `data/concepts/registry.edn`.
3. Reference from relevant Sew benchmark packs or from a new Sew-specific allocation concept entry.

**Effort:** Medium (~2h)

---

### P7: Shortfall recovery not benchmark-tested

**Problem:** The shortfall-allocation benchmark exercises three shortfall conditions but none of them test shortfall *recovery* — i.e., a deferred claim being paid in a later epoch. S82_shortfall-recovery-cycle exists in the yield-scenario suite and is covered by the Sew yield-shortfall-v1 benchmark, but there's no dedicated shortfall-recovery benchmark with explicit claims and concepts.

**Status:** Already partially covered by `sew-yield-shortfall-v1` which uses the 15-scenario yield suite (including S82). Not a gap, but the recovery dimension should be made more explicit.

**Plan:**
1. Add documentation noting that shortfall recovery coverage lives in sew-yield-shortfall-v1 (S82).
2. Optionally add a cross-reference from shortfall-allocation-v0's concepts to yield-shortfall-v1.

**Effort:** Documentation only (~15m)

---

## Completed This Session

| Item | Status |
|------|--------|
| All plan items (P1–P7) | DONE |
| P6: Slash-obligation allocation global concept | DONE |
| P6: Registry entry (15 concepts total) | DONE |
| P6: Reference from resolver-slashing-v1 benchmark | DONE |
| P7: Shortfall recovery cross-reference docs | DONE |
| `scripts/benchmarks_validate.clj` syntax fixup | DONE |

## Summary

All 7 prioritized items from the benchmark-concept-review-followup plan are complete.
The benchmark concept layer now covers the full allocation triad (partial-fill, shortfall,
pro-rata-fairness, slash-obligation) with 15 global concepts and 8 benchmarks.

**Remaining work:** No remaining items in this plan. Future work could include:
- Adding slash-obligation claims to the resolver-slashing-v1 benchmark
- Semantic (Level 3) claim evaluators for Sew claims
- Additional scenario coverage for recovery edge cases
