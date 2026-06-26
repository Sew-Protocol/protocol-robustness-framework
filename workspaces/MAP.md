# Workspace Layout

```
resolver-sim/
├── src/              ← Protocol-agnostic framework core (default view)
├── protocols_src/    ← Protocol implementations (SEW, etc.)
├── deps.edn          ← PRF-only by default; add :with-sew for full stack
└── workspaces/
    ├── prf-only/     ← Now the default — work from project root
    └── with-sew/     ← Full-stack view (src + protocols_src)
```

## Which view to use

| Goal | What to use |
|---|---|
| Browse framework core without protocol noise | Project root (`deps.edn` includes only `src/`) |
| Run full test suite (framework + Sew) | `clojure -M:test:with-sew` or `workspaces/with-sew/` |
| Run framework-only tests | `bb test:framework` or `clojure -M:test` |
| REPL with full stack | `clojure -M:dev/base:with-sew` |
| Understand the protocol abstraction | `src/resolver_sim/protocols/` |

## The two source roots

`src/` contains the protocol-agnostic framework. It never imports protocol
implementation namespaces at compile time — dispatch uses `requiring-resolve`
for lazy conditional loading.

`protocols_src/` contains concrete protocol implementations. It imports the
framework freely but is never imported by the framework at compile time.

This one-directional dependency is the key architectural invariant.
Cross-references from framework to protocol are always conditional/lazy.
