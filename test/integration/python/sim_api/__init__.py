"""
sim_api — Python adversarial scenario generator for the generic simulation system.

Architecture
------------
Python is the *exploration layer* only:
  - generates adversarial scenarios as JSON files
  - makes no correctness guarantees
  - owns no protocol state

All correctness guarantees live in the Clojure replay engine
(src/resolver_sim/contract_model/replay.clj).

Public surface
--------------
  schema      — Pydantic models + JSON Schema validation
  agents      — agent strategy classes (decide → action)
  simulation  — SimPy-based multi-agent event simulation
  generator   — Hypothesis property-based scenario generators
  cli         — CLI entry point
"""

__version__ = "0.1.0"
