(ns resolver-sim.util.math
  "Canonical financial arithmetic helpers to prevent non-deterministic precision drift.")

(defn to-canonical
  "Round down (floor) to long to ensure deterministic integer truncation."
  [f]
  (long (Math/floor (double f))))

(defn to-bps
  "Convert basis points to a multiplier."
  [bps]
  (/ (double bps) 10000.0))
