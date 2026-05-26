# Logging subsystem (`resolver-sim.logging`)

This project now includes a lightweight logging facade designed for robustness simulations.

## Goals

- zero-config default logger
- never fail if logger is absent/misconfigured
- REPL/notebook friendly
- structured events for future evidence integration
- no effect on deterministic replay state

## Namespace

`resolver-sim.logging`

## API

- `*logger*` (dynamic): current logger fn
- `*log-context*` (dynamic): ambient context map
- `(log! level message)`
- `(log! level message context-map)`
- helpers: `trace!`, `debug!`, `info!`, `warn!`, `error!`
- `(with-log-context {:run-id "..."} ...)`
- `(with-timing "label" expr...)`

Supported levels:

- `:trace`
- `:debug`
- `:info`
- `:warn`
- `:error`

## Default behavior

By default, events are printed to stdout via `prn` as EDN maps:

```clojure
{:ts "2026-05-26T17:00:00Z"
 :level :info
 :message "scenario/end"
 :context {:scenario-id "S01" :outcome :pass}}
```

## Safety guarantees

- If `*logger*` is invalid, fallback logger is used.
- If `*logger*` throws, fallback logger is attempted.
- If fallback also fails, logging silently no-ops.
- Logging never throws to caller.

## REPL usage

```clojure
(require '[resolver-sim.logging :as log])

(log/info! "starting replay" {:scenario-id "S26"})

(log/with-log-context {:run-id "run-123"}
  (log/debug! "step" {:seq 1}))

(log/with-timing "replay-run"
  (Thread/sleep 10)
  :ok)
```

## Notebook usage

```clojure
(require '[resolver-sim.logging :as log])

(log/info! "notebook/server-starting" {:port 7777})
```

## Extension path (future)

The facade is intentionally small so we can later add:

- file appenders
- structured artifact logs
- notebook telemetry streams
- OpenTelemetry adapters
- scenario correlation IDs

without changing call sites.
