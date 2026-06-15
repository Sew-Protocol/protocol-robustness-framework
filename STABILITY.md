# Stability Policy

This project is under active development. Not all code is stable.

## Stable surfaces

The following are stability-controlled:

- scenario schema and scenario semantics
- public CLI/test commands
- core regression scenarios
- artifact registry format
- emitted evidence artifacts
- invariant identifiers and meanings

Changes to these surfaces require:

- passing unit and invariant tests
- changelog entry
- migration note if behaviour changes
- updated golden outputs where applicable

## Experimental surfaces

The following are experimental:

- local workflow scripts
- jj/jujutsu automation
- exploratory notebooks
- prototype scenarios
- unfinished temporal/yield/governance refactors

Experimental surfaces may change without migration support.

## Bug fixes

Bug fixes are allowed across all surfaces.

If a bug fix changes public behaviour, the change must be documented as a correction, not silently treated as ordinary drift.
