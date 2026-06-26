# Workspace Layout

```
resolver-sim/
├── src/                     ← Protocol-agnostic framework core
├── protocols_src/           ← Protocol implementations (SEW, Yield, etc.)
├── scenarios/               ← Public scenario fixtures
├── docs/                    ← Specs and design docs
├── test/                    ← All tests
├── resources/               ← Public test vectors
└── workspaces/
    ├── prf-only/            ← Framework-only development view
    ├── with-sew/            ← Full-stack (framework + Sew protocol)
    ├── forensic-runner/     ← Evidence-producing execution template
    └── private-discovery-template/ ← Sealed research workspace template
```

## Which workspace to use

| Goal | Workspace |
|---|---|
| Browse framework core without protocol noise | `prf-only/` or project root (default `deps.edn`) |
| Run full test suite (framework + Sew) | `with-sew/` or project root with `:with-sew` alias |
| Run framework-only tests | Project root with `clojure -M:test` |
| Protocol implementation & scenario development | `with-sew/` |
| Produce forensic evidence | `forensic-runner/` → output to `~/prf-runs/<run-id>/` |
| Sensitive discovery / private collaboration | Copy template → `~/prf-private/<id>/` |
| REPL with full stack | `clojure -M:dev/base:with-sew` (from project root) |
| Understand the protocol abstraction | `src/resolver_sim/protocols/` |

## The two source roots

`src/` contains the protocol-agnostic framework. It never imports protocol
implementation namespaces at compile time — dispatch uses `requiring-resolve`
for lazy conditional loading.

`protocols_src/` contains concrete protocol implementations. It imports the
framework freely but is never imported by the framework at compile time.

This one-directional dependency is the key architectural invariant.
Cross-references from framework to protocol are always conditional/lazy.

## Workspace separation

| Workspace | Can edit source? | Forensic evidence? | Private material? | In git? |
|---|---|---|---|---|
| Project root | Yes | Candidate only | No | Yes |
| `prf-only/` | Yes (framework) | No | No | Yes |
| `with-sew/` | Yes | Candidate only | No | Yes |
| `forensic-runner/` | Prefer no | Yes (primary) | Limited | Template only |
| `private-discovery-template/` | Drafts only | Local/private only | Yes | Template only |
| `~/prf-runs/<run-id>/` | No | Yes | Usually no | No |
| `~/prf-private/<id>/` | No or drafts | Sealed local history | Yes | No |

## Forensic output

Forensic runs write to `~/prf-runs/<run-id>/` — outside the repo tree.
See `docs/forensic/FORENSIC_WORKSPACE_SPEC_V1.md`.

## Private discovery

Sensitive research workspaces live at `~/prf-private/<id>/`.
See `docs/forensic/FORENSIC_WORKSPACE_SPEC_V1.md`.
