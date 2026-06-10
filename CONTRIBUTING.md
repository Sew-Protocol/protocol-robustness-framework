
# Contributing

## Adding a new invariant

1. Define the check function in the appropriate invariants.clj file
2. Register it in the check-fns map with a keyword ID
3. Add the keyword ID to default-runtime-invariant-ids in invariant-catalog.clj
4. Add expected-failures entries to any scenarios where the invariant is known to fire
5. Run bb test:unit to verify it does not break existing scenarios
6. Run scripts/regenerate-goldens.clj to update golden reports

## Expected-failures

When a scenario deliberately triggers an invariant violation (adversarial scenarios), declare the expected failure in the scenario JSON under protocol-params:

  expected-failures:
    scenario-id: [invariant-keyword]

This suppresses the invariant check for that scenario. If the invariant passes despite being expected to fail, unused-expected-failure? is set to true in the results.
