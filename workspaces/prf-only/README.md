# PRF-Only Workspace

This workspace loads only the protocol-agnostic framework core (`src/`).
SEW protocol implementation files are on disk under `protocols_src/` but
not on the classpath.

To load:   clojure -M
To test:   clojure -M:test ...
To lint:   clojure -M:lint --lint src

Protocol implementations live outside this view.
Switch to `workspaces/with-sew/` for the full picture.
