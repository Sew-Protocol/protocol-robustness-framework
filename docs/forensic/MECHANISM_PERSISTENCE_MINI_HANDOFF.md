# Mechanism Persistence V1 Mini Handoff

Context: the complex foundation is in place as a PRF-derived semantic layer.
Keep the canonical evidence DAG semantics unchanged.

Trivial or low-risk follow-ups for gpt5.4mini:

1. Add `benchmarks/mechanisms/` to `benchmarks/README.md` directory layout.
2. Document `mechanism-map.v1` in `docs/specs/` with the official status enum:
   `passed`, `failed`, `not-exercised`, `not-applicable`, `inconclusive`,
   `evidence-missing`, `invalid-index`.
3. Add a small README example showing how to run:
   `python3 -m scripts.forensic.mechanism_persistence <run-dir> --mechanism-map benchmarks/mechanisms/shortfall-v1.edn --benchmark benchmarks/packs/prf-core/shortfall-allocation-v0.edn`
4. Add fixture examples for `mechanism-persistence-index.json`,
   `mechanism-persistence-summary.json`, and `mechanism-scenario-matrix.json`.
5. Add viewer/report copy that labels the mechanism artifacts as derived semantic
   artifacts, not canonical evidence roots.
6. Add a lightweight `verify.py` informational check that reports presence and
   schema version of mechanism-derived artifacts without making them required.
7. Add CI/python task documentation for `tests/forensic_python/test_mechanism_persistence.py`.
8. Consider adding a `:benchmark/mechanism-map` field to benchmark manifests
   after the first report/view consumes it.
