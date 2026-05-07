# Overview

## What This Is

The SEW Dispute Resolution Simulator is an adversarial security testing framework for Ethereum escrow and dispute-resolution protocols.

It was built to answer a question that existing tools cannot: **what happens when two valid transactions, from two different actors, interact in an unexpected order?**

Static analysis tools (Slither, Mythril) check individual functions. Fuzz testers (Echidna, Foundry) generate random inputs to single functions. Neither models the scenario where an attacker coordinates actions across multiple actors, across multiple blocks, in a specific sequence designed to exploit timing or state assumptions.

This simulator does exactly that.

## What Problem It Solves

Ethereum escrow protocols all share a common structure:
1. Funds are deposited
2. A condition is met (or disputed)
3. A resolver decides the outcome
4. Funds are released or refunded

The security assumptions in step 3 — who can resolve, when, under what authority, in what order — are where real-world failures occur. These assumptions are almost never tested adversarially.

This simulator provides a reusable library of failure modes, a deterministic state machine to test against, and a reproducible test harness with invariant checking at every step.

## What It Produces

Running `python invariant_suite.py` produces:
- Per-scenario pass/fail with step counts and attack metrics
- A reproducibility header (git SHA, Python version, UTC timestamp)
- Summary statistics across the adversarial scenario suite (transactions, escrow volume, attack successes, invariant violations)
- Optional JSON report for CI integration

## Who Should Use This

- **Protocol developers** building escrow or dispute-resolution systems
- **Security researchers** auditing governance and escalation mechanisms
- **Auditors** who need reproducible evidence that a specific failure class has been tested
- **Grant reviewers** evaluating the completeness of a security testing programme

## Relationship to the SEW Protocol

This simulator was developed for the SEW (Simple Escrow with Waterfall) protocol, which implements a multi-level dispute resolution system with Kleros-style escalation. The failure modes tested are structural and apply to any protocol sharing these properties.
