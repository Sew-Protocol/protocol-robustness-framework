# Reproduce this evidence pack

Run: `run-2026-06-28T22:41:16.803733951Z`
Suite: `sew-reference-v1` (7 scenarios)
Commitment: `b093d79e8245e220e9e1cbd6cac990ef1143e2474c0a250a18bfa45bec792226`
DAG root: `60eb3fc21889b72e1dbc64498f2d324ac6d88ebaa28b5efb7505ee37b25b909d`

## Prerequisites

1. Java 21+
2. `prf-runner-sew.jar` (from the release)
3. The scenario trace files below

## Scenario files

- `data/fixtures/traces/s-auto-cancel-time-via-keeper.trace.json`
- `data/fixtures/traces/s-auto-cancel-time-boundary.trace.json`
- `data/fixtures/traces/s-auto-cancel-time-orphaned-by-dispute.trace.json`
- `data/fixtures/traces/s-same-timestamp-auto-cancel-vs-dispute.trace.json`
- `data/fixtures/traces/s-same-timestamp-dispute-vs-auto-cancel.trace.json`
- `data/fixtures/traces/s-extortion-unilateral-cancel.trace.json`
- `data/fixtures/traces/s-extortion-unilateral-cancel-dual.trace.json`

## Reproduce

```bash
java -jar prf-runner-sew.jar \
  -m resolver-sim.minimal-runner \
  --suite sew-reference-v1 \
  --fixtures ./fixtures
```

## Source

Tree hash: `482d6b483e9ed1baf5694174737e335c1a5c4e26cbfb578994f9deeccec0dd29`
Commit: `f0ab6d441687e093fa7db8f05d8b5945c98b632a`
