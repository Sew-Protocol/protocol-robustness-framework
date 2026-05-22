(ns resolver-sim.adversaries.ring-attacker
  "RingAttack adversary for multi-epoch equity trajectory analysis (Phase AH / AI).

   Models a coordinated ring of resolvers that rotate disputes among members to
   keep per-member verdict counts below the detection threshold.

   Key mechanics:
   - Each ring member handles 1/ring-size of all disputes the ring is assigned.
   - Effective detection probability per member = base-detection / ring-size.
   - The ring as a whole earns the same gross profit as an individual attacker,
     but survives longer before any single member is slashed.

   detection-avoidance-rate = 1 - (effective-detection / base-detection)
                             = 1 - (1 / ring-size)

   This adversary emits labelled trajectory output:
     {:trajectory/class      :ring-evasion
      :ring-size             N
      :detection-avoidance-rate R
      :equity-trajectories   {...}}

   Layering: adversaries/* may import model/* only. No db/*, io/* imports."
  (:require [resolver-sim.adversaries.strategy :as strategy]
            [resolver-sim.sim.multi-epoch :as me]
            [resolver-sim.sim.trajectory :as trajectory]
            [resolver-sim.stochastic.rng :as rng]))

;; ---------------------------------------------------------------------------
;; RingAttacker — implements the Adversary protocol
;; ---------------------------------------------------------------------------

(deftype RingAttacker [ring-size rotation-period detection-evasion-bps]
  strategy/Adversary

  (should-attack? [_ _params]
    ; Ring always attacks — it has already been formed.
    true)

  (attack-type [_ _params]
    :ring-rotation)

  (budget-allocation [_ _params]
    {:ring-rotation 1.0 :bribery 0.0 :evidence 0.0 :collusion 0.0})

  (expected-profit [_ _attack-type params]
    (let [escrow       (:escrow-size params 10000)
          fee-bps      (:resolver-fee-bps params 150)
          base-det     (:fraud-detection-probability params 0.05)
          eff-det      (/ base-det ring-size)
          slash-mult   (:slash-multiplier params 2.0)
          fee          (* escrow (/ fee-bps 10000.0))
          ; Expected profit = fee - (effective detection rate × slashing loss)
          slash-loss   (* escrow slash-mult eff-det)]
      (- fee slash-loss)))

  (observe-outcome! [_ _outcome]
    nil))

;; ---------------------------------------------------------------------------
;; Ring-adjusted epoch params
;; ---------------------------------------------------------------------------

(defn- ring-adjusted-params
  "Return params with detection probability reduced to model ring rotation.

   The ring spreads disputes across ring-size members, so each member's effective
   per-dispute detection probability is base / ring-size."
  [params ring-size]
  (let [reduce-prob (fn [p] (double (/ (or p 0.0) ring-size)))]
    (assoc params
           :fraud-detection-probability    (reduce-prob (:fraud-detection-probability params 0.05))
           :slashing-detection-probability (reduce-prob (:slashing-detection-probability params 0.10))
           :reversal-detection-probability (reduce-prob (:reversal-detection-probability params 0.03)))))

;; ---------------------------------------------------------------------------
;; Per-ring-size multi-epoch run
;; ---------------------------------------------------------------------------

(defn run-ring-trial
  "Run a multi-epoch simulation with a ring of size ring-size as the attack cohort.

   params must include :n-epochs and :n-trials-per-epoch.
   The malicious cohort uses ring-adjusted detection probabilities.

   Returns the standard multi-epoch result map annotated with ring metadata."
  [ring-size params]
  (let [seed     (+ (:seed params 42) (* ring-size 13))
        d-rng    (rng/make-rng seed)
        adj-p    (ring-adjusted-params params ring-size)
        n-epochs (:n-epochs params 100)
        n-trials (:n-trials-per-epoch params 200)
        result   (me/run-multi-epoch d-rng n-epochs n-trials adj-p)

        base-det (:fraud-detection-probability params 0.05)
        eff-det  (/ base-det ring-size)
        avoidance-rate (if (pos? base-det)
                         (double (- 1.0 (/ eff-det base-det)))
                         0.0)]
    (assoc result
           :trajectory/class        :ring-evasion
           :ring-size               ring-size
           :detection-avoidance-rate avoidance-rate
           :effective-detection      (double eff-det))))

;; ---------------------------------------------------------------------------
;; Ring-size sweep
;; ---------------------------------------------------------------------------

(defn run-ring-sweep
  "Sweep ring-size ∈ ring-sizes and return per-ring-size trajectory data.

   params — see data/params/phase-ah-trajectory-sweep.edn for field reference.
   ring-sizes — vector of sizes to sweep (default [2 3 5 8 13]).

   Returns:
     {:ring-sizes     [2 3 5 8 13]
      :trials         [{ring-size result}]
      :optimal-size   N   ; ring size with highest strategic equity at final epoch
      :trajectory/class :ring-evasion}"
  ([params] (run-ring-sweep params [2 3 5 8 13]))
  ([params ring-sizes]
   (println "\n🔁 Ring-size sweep:" (pr-str ring-sizes))
   (let [trials
         (mapv (fn [sz]
                 (println (format "   Ring size %2d: running %d epochs …" sz (:n-epochs params 100)))
                 (let [result (run-ring-trial sz params)
                       stats  (:aggregated-stats result)]
                   (println (format "              avoidance=%.0f%%  malice-cum=%.0f  honest-cum=%.0f"
                                    (* 100 (:detection-avoidance-rate result))
                                    (:malice-cumulative-profit stats 0.0)
                                    (:honest-cumulative-profit stats 0.0)))
                   {:ring-size               sz
                    :detection-avoidance-rate (:detection-avoidance-rate result)
                    :effective-detection      (:effective-detection result)
                    :malice-cumulative-profit (:malice-cumulative-profit stats 0.0)
                    :honest-cumulative-profit (:honest-cumulative-profit stats 0.0)
                    :equity-trajectories      (:equity-trajectories result {})
                    :strategy-spread-trajectories (:strategy-spread-trajectories result [])}))
               ring-sizes)

         ; Ring with highest strategic cumulative profit is the "optimal" evasion size.
         optimal (apply max-key :malice-cumulative-profit trials)]

     (println (format "\n   Optimal ring size: %d (avoidance=%.0f%%)"
                      (:ring-size optimal)
                      (* 100 (:detection-avoidance-rate optimal))))

     {:ring-sizes       ring-sizes
      :trials           trials
      :optimal-size     (:ring-size optimal)
      :trajectory/class :ring-evasion})))
