# Archived Roadmap: Architecture Generalization (Historical)

> Status: Archived planning document.
>
> This document captures a historical architecture direction that is **not** the
> current repo claim. The current positioning is: SEW validation implementation
> built on a protocol-adapter replay harness and deterministic fixture tooling.

## Historical Objectives
1.  **Decoupling**: Separate the core simulation engine (the substrate) from protocol-specific rules (the plugins).
2.  **Standardization**: Adopt the **Common Dispute Resolution Standard (CDRS)** draft schema as the native trace and event format.
3.  **Extensibility**: Enable third-party protocols to leverage **CDRS-shaped** testing for adversarial stress-testing.

## Historical Future Architecture
This section records a proposed structure that may inform future refactors:

### 1. The Kernel Layer (`src/engine/`)
A neutral substrate for state transitions, world-state hashing, and atomic reconciliation. It remains agnostic to "disputes" or "resolvers," focusing only on state machines and economic invariants.

### 2. The Domain Layer (`src/domain/dispute/`)
Standardizes the language of decentralized dispute resolution. It defines the CDRS state buckets (`IDLE`, `CHALLENGED`, etc.) and canonical resolution outcomes.

### 3. The Implementation Layer (`src/protocols/`)
Concrete implementation modules (like `sew/` and `uma/`) that map their internal state graphs to the domain layer's standards.

## Timeline (Historical)
This refactor concept was scoped as post-launch. Current engineering emphasis in
this repository remains SEW-specific robustness, deterministic replay, and trace
verification.
