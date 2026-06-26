# Workspace Layout

```
resolver-sim/
├── src/              ← Protocol-agnostic framework core
├── protocols_src/    ← Protocol implementations (SEW, etc.)
├── deps.edn          ← Canonical: src + protocols_src (the full view)
└── workspaces/
    └── prf-only/     ← Framework-only view (excludes protocols_src)
```

## Which view to use

| Goal | What to use |
|---|---|
| Browse framework core without protocol noise | `workspaces/prf-only/` |
| Run tests, REPL, or any production task | Project root (`deps.edn` includes both roots) |
| Add a new protocol implementation | Browse `workspaces/prf-only/` to see dispatch points |
| Understand the protocol abstraction | `src/resolver_sim/protocols/` |

## The two source roots

`src/` contains the protocol-agnostic framework. It never imports protocol
implementation namespaces at compile time — dispatch uses `requiring-resolve`
for lazy conditional loading.

`protocols_src/` contains concrete protocol implementations. It imports the
framework freely but is never imported by the framework at compile time.

This one-directional dependency is the key architectural invariant.
Cross-references from framework to protocol are always conditional/lazy.
