# Namespace Semantics: Conceptual Boundary vs Implementation Namespace

This document clarifies a recurring source of confusion:

- **Conceptual/public framing**: **Protocol Robustness Framework**
- **Implementation namespace root**: `resolver_sim` (Clojure ns: `resolver-sim.*`)

These are intentionally different concerns.

## 1) Public conceptual boundary

The public conceptual system boundary is the **Protocol Robustness Framework**:

- adapter-oriented robustness framework,
- replay + protocol adapter substrate,
- scenario-analysis and accounting evidence surfaces,
- reference-study-driven evolution (Sew first, additional adapters later).

This is the architectural/product framing contributors and external readers
should use.

## 2) Stable implementation namespace boundary

The implementation is rooted at:

- file path root: `src/resolver_sim/*`
- Clojure namespace root: `resolver-sim.*`

This root is a **stable implementation namespace**, not the public conceptual
boundary.

It exists for code organization and compatibility, not to define product scope.

## 3) Why this distinction matters

If we conflate the two, contributors may incorrectly infer:

- that `resolver_sim` equals Sew-only scope,
- or that naming implies hard protocol lock-in,
- or that framework/adapters/research tracks are not intentionally separated.

Instead:

- **framework boundary** is defined by contracts and docs,
- **namespace root** is an implementation detail that remains stable while
  architecture evolves.

## 4) Practical reading guidance

- Read **framework boundaries** from:
  - `docs/framework-boundaries.md`
  - `docs/architecture/ARCHITECTURE.md`
- Read **implementation layout** from:
  - `docs/architecture/layers.md`
  - namespace tree under `src/resolver_sim/*`

## 5) One-line policy

Use this phrasing in docs/reviews:

> **Protocol Robustness Framework** (conceptual boundary),
> implemented under stable namespace root **`resolver_sim`**.
