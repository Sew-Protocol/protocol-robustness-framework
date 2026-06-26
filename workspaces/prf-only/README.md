# PRF-Only View

This view is now the default — work from the project root.
The root `deps.edn` includes only `src/` (framework core).

For the full stack (framework + Sew protocol), use `:with-sew` alias:
  clojure -M:with-sew
  clojure -M:test:with-sew
  clojure -M:dev/base:with-sew

Or switch to `workspaces/with-sew/`.
