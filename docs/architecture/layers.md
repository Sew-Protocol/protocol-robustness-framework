# Architecture Layers Map

This page maps major namespaces to one primary architectural layer:

- **Framework Substrate**
- **Adapter Contract**
- **Reference Implementation**
- **Research Track**

> Rule of thumb: classification follows dominant intent, not every incidental use.

## Layer definitions

| Layer | Meaning |
|---|---|
| Framework Substrate | Reusable mechanics/infrastructure that should remain protocol-agnostic (or adapter-pluggable). |
| Adapter Contract | Interface boundary and adapter wiring points. |
| Reference Implementation | Primary concrete protocol implementation (SEW) and tightly coupled modules. |
| Research Track | Exploratory/phase-study modules and evidence-generation code not treated as stable framework API. |

## Namespace → layer mapping

| Namespace / Path | Layer | Notes |
|---|---|---|
| `resolver-sim.contract-model.*` | Framework Substrate | Deterministic replay kernel, protocol-agnostic orchestration. |
| `resolver-sim.protocols.protocol` | Adapter Contract | Core protocol interface contracts. |
| `resolver-sim.protocols.registry` | Adapter Contract | Adapter registry/wiring boundary. |
| `resolver-sim.protocols.common.*` | Framework Substrate | Reusable adapter flow-control wrappers. |
| `resolver-sim.protocols.dummy` | Adapter Contract | Minimal reference for adapter conformance. |
| `resolver-sim.protocols.sew` | Reference Implementation | SEW adapter façade implementation. |
| `resolver-sim.protocols.sew.*` | Reference Implementation | SEW domain semantics and invariants. |
| `resolver-sim.scenario.*` | Framework Substrate | Scenario analysis/projection/evaluation substrate. |
| `resolver-sim.time.model` | Framework Substrate | Generic temporal model primitives. |
| `resolver-sim.time.deadlines` | Framework Substrate | Time/deadline mechanics usable across adapters. |
| `resolver-sim.time.invariants` | Framework Substrate | Temporal invariant scaffolding/helpers. |
| `resolver-sim.db.*` | Framework Substrate | Persistence shell and run/event storage infrastructure. |
| `resolver-sim.io.scenarios` | Framework Substrate | Scenario loading/validation substrate. |
| `resolver-sim.io.trace-score` | Framework Substrate | Generic scoring façade (delegates to protocol-specific providers). |
| `resolver-sim.io.sew.trace-score` | Reference Implementation | SEW-specific scoring semantics. |
| `resolver-sim.server.*` | Framework Substrate | Session/server infrastructure with protocol pluggability. |
| `resolver-sim.generators.actions` | Framework Substrate | Generic generation orchestration façade. |
| `resolver-sim.generators.adversarial` | Framework Substrate | Generic adversarial generation façade. |
| `resolver-sim.generators.sew.*` | Reference Implementation | SEW-specific action/adversarial templates. |
| `resolver-sim.economics.payoffs` | Reference Implementation | Currently SEW-aligned economic defaults. |
| `resolver-sim.yield.accounting` | Reference Implementation | SEW-integrated accounting mechanics (reusable arithmetic, SEW world assumptions). |
| `resolver-sim.yield.registry` | Reference Implementation | SEW-integrated module registry/policy assumptions. |
| `resolver-sim.yield.modules.*` | Reference Implementation | Current yield modules integrated to SEW semantics. |
| `resolver-sim.sim.minimizer` | Framework Substrate | Protocol-agnostic minimization harness. |
| `resolver-sim.sim.phase-z-scenarios` | Research Track | Phase/scenario study module for robustness exploration. |
| `resolver-sim.sim.adversarial.*` | Research Track | Exploratory adversarial studies. |
| `resolver-sim.sim.phase_*` / phase modules | Research Track | Hypothesis/phase experiments unless promoted explicitly. |
| `resolver-sim.stochastic.*` | Research Track | Statistical/economic exploration models. |
| `resolver-sim.adversaries.*` | Research Track | Strategy-model exploration layer. |
| `resolver-sim.oracle.*` | Research Track | Detection-model research layer. |

## Promotion guidance

Move a namespace from **Research Track** to **Framework Substrate** only when:

1. Its semantics are adapter-agnostic,
2. It has stable interfaces and tests,
3. It avoids encoding SEW-specific economic meaning,
4. Its guarantees are documented in architecture docs.

Related references:

- `docs/architecture/ARCHITECTURE.md`
- `docs/architecture/ADAPTER_AUTHORING_GUIDE.md`
- `docs/architecture/RESEARCH_BOUNDARY.md`
- `docs/overview/GENERALISATION_MATRIX.md`
