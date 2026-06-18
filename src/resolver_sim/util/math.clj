(ns resolver-sim.util.math
  "Canonical financial arithmetic helpers to prevent non-deterministic precision drift.")

(defn to-canonical
  "Ensure deterministic integer truncation.
   Accepts arbitrary-precision integers."
  [n]
  (bigint n))

(defn to-bps
  "Convert basis points to a multiplier."
  [bps]
  (/ (double bps) 10000.0))
