(ns resolver-sim.time.deadlines
  "Deterministic deadline arithmetic helpers for simulation time.")

(defn deadline
  "Compute absolute deadline from start-ts + duration-seconds."
  [start-ts duration-seconds]
  (+ (long start-ts) (long duration-seconds)))

(defn before-deadline?
  [now-ts deadline-ts]
  (< (long now-ts) (long deadline-ts)))

(defn at-deadline?
  [now-ts deadline-ts]
  (= (long now-ts) (long deadline-ts)))

(defn deadline-passed?
  [now-ts deadline-ts]
  (> (long now-ts) (long deadline-ts)))

(defn boundary-times
  "Return canonical boundary probes around a deadline: t-1, t, t+1."
  [deadline-ts]
  {:t-1 (dec (long deadline-ts))
   :t   (long deadline-ts)
   :t+1 (inc (long deadline-ts))})
