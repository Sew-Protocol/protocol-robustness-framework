(ns resolver-sim.sim.waterfall
  "Waterfall stress testing for coverage adequacy
    
   The waterfall mechanism is a three-phase slashing order that protects
   the system when resolver bonds are depleted:
    
   Phase 1: Slash resolver's own bond first (50% per slash, 20% per epoch cap)
   Phase 2: If exhausted, slash senior's coverage pool (10% per epoch cap)
   Phase 3: If coverage exhausted, unmet obligations tracked
    
   This module simulates the waterfall under various stress conditions to
   determine minimum senior coverage requirements.
    
   Two execution modes:
   - Deterministic (`process-slash-event`): all events are processed at full
     slash-amount; no probability. Tests pool depth capacity.
   - Probabilistic (`probabilistic-process-slash-pool`): each event is filtered
     through Monte Carlo dispute resolution; slash frequency and amount reflect
     real detection probabilities. Tests pool robustness."
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.stochastic.dispute :as dispute]))

(declare aggregate-waterfall-metrics)

(defn calculate-slash-amount
  "Calculate actual slash amount given bond and slash rate (in basis points)
   
   Applies per-slash cap (50% of bond) and ensures non-negative.
   
   Args:
     bond-amount: Resolver's bond in stables
     slash-rate-bps: Slash rate in basis points (e.g., 50 = 0.5%)
   
   Returns: Amount to slash (before waterfall)"
  [bond-amount slash-rate-bps]
  (let [per-slash-cap (/ bond-amount 2.0)  ; 50% per-slash limit
        calc-amount (* bond-amount (/ slash-rate-bps 10000.0))]
    (double (min per-slash-cap calc-amount))))

(defn apply-junior-slash
  "Slash resolver's own bond (Phase 1)
   
   Returns: {:actually-slashed amount-taken
             :shortage amount-not-covered
             :new-resolver updated-resolver-state}"
  [resolver slash-amount]
  (let [bond-before (:bond-remaining resolver 0)
        per-slash-cap (/ bond-before 2.0)
        capped-slash (min slash-amount per-slash-cap)
        amount-slashed (min bond-before capped-slash)
        shortage (- slash-amount amount-slashed)
        bond-after (max 0 (- bond-before amount-slashed))
        is-exhausted (zero? bond-after)]
    {:actually-slashed (double amount-slashed)
     :shortage (double shortage)
     :new-resolver (-> resolver
                       (assoc :bond-remaining (double bond-after))
                       (assoc :is-exhausted? is-exhausted)
                       (update :slash-history (fnil conj [])
                               {:phase :junior
                                :amount (double amount-slashed)
                                :epoch (:current-epoch resolver 0)}))}))

(defn calculate-available-coverage
  "Calculate available coverage for a senior
   
   Available = (bond × utilization) - reserved-for-juniors
   
   Args:
     senior: Senior resolver state with:
       :bond-amount - Senior's bond
       :utilization-factor - % of bond available for coverage (e.g. 0.5)
       :coverage-used - Already-allocated coverage
   
   Returns: Available coverage amount"
  [senior]
  (let [max-coverage (* (:bond-amount senior 0)
                       (:utilization-factor senior 0.5))
        used (:coverage-used senior 0)
        available (max 0 (- max-coverage used))]
    (double available)))

(defn apply-senior-slash
  "Slash senior's coverage pool (Phase 2)
   
   Only triggered if junior's bond was insufficient.
   Applies 10% per-epoch cap (vs. 20% for juniors).
   
   Returns: {:senior-slashed amount-taken
             :unmet-obligation amount-not-covered}"
  [senior shortage]
  (let [available (calculate-available-coverage senior)
        ;; Senior per-epoch cap is 10% of bond
        max-per-epoch (* (:bond-amount senior 0) 0.10)
        can-slash (min available max-per-epoch shortage)
        unmet (- shortage can-slash)
        new-used (+ (:coverage-used senior 0) can-slash)]
    {:senior-slashed (double can-slash)
     :unmet-obligation (double unmet)
     :new-senior (-> senior
                     (assoc :coverage-used (double new-used))
                     (update :slash-history (fnil conj [])
                             {:phase :senior
                              :amount (double can-slash)
                              :epoch (:current-epoch senior 0)}))}))

(defn apply-waterfall-slash
  "Execute complete three-phase waterfall slash
   
   1. Slash junior's bond first (up to 50% per slash)
   2. If insufficient, slash senior's coverage (up to 10% per epoch)
   3. Track unmet obligations for reporting
   
   Args:
     junior: Junior resolver state
     senior: Senior resolver state (or nil if not delegated)
     slash-amount: Total amount to slash
   
   Returns:
     {:junior updated-junior
      :senior updated-senior (or nil)
      :junior-paid amount-slashed-from-junior
      :senior-paid amount-slashed-from-senior
      :unmet-obligation amount-not-covered
      :phases-executed [phase1 phase2 phase3]}"
  [junior senior slash-amount]
  (let [;; Phase 1: Slash junior
        phase1 (apply-junior-slash junior slash-amount)
        junior-slashed (:actually-slashed phase1)
        shortage (:shortage phase1)
        
        ;; Phase 2: Slash senior if needed (and available)
        phase2 (if (and (> shortage 0) senior)
                 (apply-senior-slash senior shortage)
                 {:senior-slashed 0
                  :unmet-obligation shortage
                  :new-senior senior})
        
        senior-slashed (:senior-slashed phase2)
        unmet (:unmet-obligation phase2)]
    
    {:junior (:new-resolver phase1)
     :senior (:new-senior phase2)
     :junior-paid (double junior-slashed)
     :senior-paid (double senior-slashed)
     :unmet-obligation (double unmet)
     :phases-executed [:phase1 :phase2]}))

(defn process-slash-event
  "Process a single slash event through the waterfall
   
   Args:
     event: {:resolver-id string
             :slash-amount double
             :reason keyword (e.g., :fraud, :timeout, :repeat)}
     resolvers: map of {resolver-id -> resolver-state}
     seniors: map of {senior-id -> senior-state}
   
   Returns:
     {:resolvers updated-map
      :seniors updated-map
      :event-result {:junior-paid double
                     :senior-paid double
                     :unmet-obligation double}
      :metrics {...}}"
  [event resolvers seniors]
  (let [resolver-id (:resolver-id event)
        resolver (get resolvers resolver-id)
        senior-id (or (:senior-id event) (:senior-delegation resolver))
        senior (when senior-id (get seniors senior-id))]
    
    (if (nil? resolver)
      ;; Resolver not found - shouldn't happen
      {:resolvers resolvers
       :seniors seniors
       :event-result {:junior-paid 0
                      :senior-paid 0
                      :unmet-obligation (:slash-amount event)
                      :error :resolver-not-found}}
      
      ;; Process waterfall slash
      (let [result (apply-waterfall-slash
                     (assoc resolver :current-epoch (:epoch event 0))
                     (when senior (assoc senior :current-epoch (:epoch event 0)))
                     (:slash-amount event))
            
            updated-resolvers (assoc resolvers resolver-id (:junior result))
            updated-seniors (if (and senior-id (:senior result))
                             (assoc seniors senior-id (:senior result))
                             seniors)]
        
        {:resolvers updated-resolvers
         :seniors updated-seniors
         :event-result {:junior-paid (:junior-paid result)
                       :senior-paid (:senior-paid result)
                       :unmet-obligation (:unmet-obligation result)
                       :reason (:reason event)
                       :resolver-id resolver-id
                       :senior-id senior-id}}))))

;; ---
;; Per-epoch cap enforcement
;; ---

(def junior-epoch-cap-pct 0.20)
(def senior-epoch-cap-pct 0.10)

(defn- total-slashed-in-epoch
  "Sum of all slashes applied to a resolver in a given epoch."
  [resolver epoch]
  (->> (:slash-history resolver [])
       (filter #(= (:epoch %) epoch))
       (map :amount)
       (reduce + 0.0)))

(defn- apply-per-epoch-cap
  "Cap slash-amount to respect the per-epoch cap.
   Returns reduced slash amount (0 if cap already exceeded)."
  [resolver slash-amount epoch cap-rate initial-bond]
  (let [already (total-slashed-in-epoch resolver epoch)
        max-allowed (* initial-bond cap-rate)
        remaining (- max-allowed already)]
    (max 0.0 (min (double slash-amount) (double remaining)))))

;; ---
;; Probabilistic waterfall — Monte Carlo powered
;; ---

(defn draw-lognormal
  "Sample from a lognormal distribution using Box-Muller transform."
  [rng-inst mu sigma]
  (let [u1 (max 1e-10 (rng/next-double rng-inst))
        u2 (rng/next-double rng-inst)
        z  (* (Math/sqrt (* -2.0 (Math/log u1))) (Math/cos (* 2.0 Math/PI u2)))]
    (Math/exp (+ mu (* sigma z)))))

(defn draw-escrow-size
  "Draw a random escrow amount from the distribution spec.
   Distribution spec: {:type :lognormal :mean N :std N}"
  [rng-inst dist]
  (let [mu   (Math/log (:mean dist 10000))
        sigma (/ (:std dist 5000) (:mean dist 10000))]
    (long (max 1 (draw-lognormal rng-inst mu sigma)))))

(defn draw-strategy
  "Draw a resolver strategy from a weighted mix.
   Mix spec: {:honest 0.80 :malicious 0.05 ...}
   Weights must sum to approximately 1.0 (±0.001 tolerance).
   Throws if validation fails — catches stale param files early.
   Sorts pairs by ascending weight so low-roll mass falls on
   low-probability strategies first."
  [rng-inst mix]
  (let [total (reduce + (vals mix))]
    (when (< 0.001 (Math/abs (- total 1.0)))
      (throw (ex-info (str "Strategy mix weights must sum to 1.0, got " total)
                      {:mix mix :total total})))
    (let [roll (rng/next-double rng-inst)
          pairs (sort-by second (map (fn [[k v]] [k v]) mix))]
      (loop [[[strat pct] & rest] pairs
             cumulative 0.0]
        (let [next-cum (+ cumulative pct)]
          (if (or (nil? strat) (< roll next-cum))
            (or strat :honest)
            (recur rest next-cum)))))))

(defn probabilistic-process-slash-pool
  "Run N dispute trials through the waterfall using full MC economics.
   
   Each trial calls resolve-dispute to determine if and how much the resolver
   is slashed.  Only slashed disputes affect the pool — detection probability,
   slash severity, and escrow variance are all reflected in the result.
   
   Per-epoch caps (20% junior, 10% senior of initial bond) are enforced
   so that no resolver loses more than the protocol allows per epoch.
   
   Args:
     rng       — PRNG (SplittableRandom)
     pool      — {:juniors {...} :seniors {...}} from initialize-waterfall-pool
     params    — parameter map with MC keys:
                 :escrow-distribution, :strategy-mix, :resolver-fee-bps,
                 :appeal-bond-bps, :slash-multiplier,
                 :appeal-probability-if-correct, :appeal-probability-if-wrong,
                 :slashing-detection-probability, :reversal-detection-probability,
                 :timeout-slash-bps, :fraud-slash-bps
     n-trials  — number of disputes to simulate
   
   Returns:
     Same shape as deterministic reduce:
     {:resolvers {...} :seniors {...} :events [event-result ...]}
     plus :metrics and :dispute-summary"
  [rng-inst pool params n-trials]
  (let [n-juniors (count (:juniors pool))
        n-per-senior (/ n-juniors (max 1 (:n-seniors params 5)))
        mc-kwargs (merge
                   {:fraud-slash-bps (:fraud-slash-bps params 50)
                    :reversal-detection-probability (:reversal-detection-probability params 0.02)
                    :timeout-slash-bps (:timeout-slash-bps params 25)}
                   ;; Forward oracle fixture params so resolve-dispute can detect
                   ;; exhaustion (needed for :repeat-last / :cycle policy checks)
                   (when-let [f (:oracle-fixture params)]
                     {:oracle-fixture f})
                   (when (:oracle-mode params)
                     {:oracle-mode (:oracle-mode params)})
                   (when (:oracle-roll-on-exhaustion params)
                     {:oracle-roll-on-exhaustion (:oracle-roll-on-exhaustion params)})
                   (when (:oracle-scope params)
                     {:oracle-scope (:oracle-scope params)}))
        result (reduce
                (fn [state trial-idx]
                  ;; Fork per-trial RNG so escrow/strategy draws don't shift
                  ;; dispute resolution rolls across trials.  Each trial is
                  ;; reproducible independently of neighboring trials.
                  (let [trial-rng (rng/make-rng (rng/seed-from-index
                                                  (:base-seed params 42) trial-idx))
                        junior-idx (mod trial-idx n-juniors)
                        senior-idx (int (/ junior-idx n-per-senior))
                        resolver-id (str "j" senior-idx "_" (mod junior-idx n-per-senior))
                        senior-id   (str "s" senior-idx)
                        epoch       (int (/ trial-idx n-juniors))

                        resolver (get (:resolvers state) resolver-id)
                        initial-bond (or (:initial-bond resolver) (:bond-remaining resolver 0) 0.0)

                        escrow-wei (draw-escrow-size trial-rng (:escrow-distribution params))
                        strategy   (draw-strategy trial-rng (:strategy-mix params))

                        outcome (dispute/resolve-dispute
                                 trial-rng escrow-wei
                                 (:resolver-fee-bps params 150)
                                 (:appeal-bond-bps params 50)
                                 (:slash-multiplier params 2.5)
                                 strategy
                                 (:appeal-probability-if-correct params 0.3)
                                 (:appeal-probability-if-wrong params 0.7)
                                 (:slashing-detection-probability params 0.10)
                                 mc-kwargs)

                        bond-loss (:bond-loss outcome 0)
                        oracle-exhausted? (boolean (:oracle-fixture/exhausted? outcome))
                        oracle-warnings   (or (:oracle-fixture/warnings outcome) [])]

                    (if (and (:slashed? outcome) (pos? bond-loss))
                      (let [;; Per-epoch cap on senior side
                            senior (get (:seniors state) senior-id)
                            senior-bond (get-in senior [:initial-bond] (get-in senior [:bond-amount] 0))
                            capped-loss (apply-per-epoch-cap resolver bond-loss epoch
                                                             junior-epoch-cap-pct initial-bond)
                            {:keys [resolvers seniors event-result]}
                            (process-slash-event
                             {:resolver-id resolver-id
                              :senior-id senior-id
                              :slash-amount capped-loss
                              :reason (:slashing-reason outcome)
                              :epoch epoch
                              :escrow-wei escrow-wei
                              :strategy strategy}
                             (:resolvers state)
                             (:seniors state))]
                        {:resolvers resolvers
                         :seniors seniors
                         :events (conj (:events state)
                                       (assoc event-result
                                              :escrow-wei escrow-wei
                                              :strategy strategy
                                              :slashed? true
                                              :oracle-exhausted? oracle-exhausted?
                                              :oracle-warnings oracle-warnings))})
                      ;; Not slashed or no bond loss: pool unchanged, record dispute
                      (update state :events conj
                              {:slashed? false
                               :reason (:slashing-reason outcome)
                               :strategy strategy
                               :escrow-wei escrow-wei
                               :oracle-exhausted? oracle-exhausted?
                               :oracle-warnings oracle-warnings}))))
                {:resolvers (:juniors pool) :seniors (:seniors pool) :events []}
                (range n-trials))
        slash-events (filter :slashed? (:events result))
        metrics (aggregate-waterfall-metrics (:resolvers result) (:seniors result) slash-events)]
    (assoc result :metrics metrics)))

(defn aggregate-waterfall-metrics
  "Aggregate waterfall metrics across all slashing events
   
   Args:
     resolvers: Final resolver state map
     seniors: Final senior state map
     events: All slash events processed
   
   Returns:
     {:juniors-exhausted-count int
      :juniors-exhausted-pct double
      :avg-junior-bond-remaining double
      :seniors-coverage-used-avg-pct double
      :seniors-at-capacity-count int
      :total-slashes int
      :total-slashed-by-junior double
      :total-slashed-by-senior double
      :total-unmet-obligation double
      :waterfall-saturation-pct double
      :coverage-adequacy-score double}"
  [resolvers seniors events]
  
  (let [junior-resolvers (filter #(not (:is-senior? (val %))) resolvers)
        senior-resolvers (filter #(:is-senior? (val %)) seniors)
        
        ;; Junior stats
        n-juniors (count junior-resolvers)
        exhausted (count (filter #(:is-exhausted? (val %)) junior-resolvers))
        avg-bond-remaining (if (empty? junior-resolvers)
                            0.0
                            (double (/ (reduce + (map #(:bond-remaining (val %) 0) junior-resolvers))
                                      (count junior-resolvers))))
        
        ;; Senior stats
        total-seniors (count senior-resolvers)
        coverage-usages (map #(let [senior (val %)
                                    available (calculate-available-coverage senior)
                                    max-coverage (* (:bond-amount senior 0) 
                                                   (:utilization-factor senior 0.5))
                                    used (:coverage-used senior 0)]
                               (if (zero? max-coverage) 0.0
                                 (/ used max-coverage)))
                           senior-resolvers)
        avg-coverage-pct (if (empty? coverage-usages)
                          0.0
                          (double (* 100.0 (/ (reduce + coverage-usages) (count coverage-usages)))))
        at-capacity (count (filter #(>= % 0.95) coverage-usages))
        
        ;; Event stats
        total-events (count events)
        slashed-by-junior (reduce + (map #(:junior-paid %) events))
        slashed-by-senior (reduce + (map #(:senior-paid %) events))
        total-unmet (reduce + (map #(:unmet-obligation %) events))
        
        ;; Derived metrics
        total-slashed (+ slashed-by-junior slashed-by-senior)
        waterfall-saturation (if (zero? total-slashed)
                              0.0
                              (double (* 100.0 (/ slashed-by-senior total-slashed))))
        adequacy (cond
            (zero? total-events) 100.0
            (zero? total-slashed) 0.0  ; No payout despite events = 0% adequacy
            :else (double (* 100.0 (- 1.0 (/ total-unmet total-slashed)))))]
    
    {:juniors-exhausted-count exhausted
     :juniors-exhausted-pct (if (zero? n-juniors)
                              0.0
                              (double (* 100.0 (/ exhausted n-juniors))))
     :avg-junior-bond-remaining avg-bond-remaining
     :seniors-coverage-used-avg-pct avg-coverage-pct
     :seniors-at-capacity-count at-capacity
     :total-slashes total-events
     :total-slashed-by-junior slashed-by-junior
     :total-slashed-by-senior slashed-by-senior
     :total-unmet-obligation total-unmet
     :waterfall-saturation-pct waterfall-saturation
     :coverage-adequacy-score adequacy}))

(defn initialize-waterfall-pool
  "Initialize senior and junior resolver pools for waterfall stress testing
   
   Args:
     params: {:n-seniors int
              :n-juniors-per-senior int
              :senior-bond-amount double
              :junior-bond-amount double
              :coverage-multiplier double
              :utilization-factor double}
   
   Returns:
     {:seniors {senior-id -> state}
      :juniors {junior-id -> state}}"
  [params]
  (let [n-seniors (:n-seniors params 5)
        n-juniors-per-senior (:n-juniors-per-senior params 10)
        senior-bond (:senior-bond-amount params 100000)
        junior-bond (:junior-bond-amount params 500)
        util-factor (:utilization-factor params 0.5)]
    
    {:seniors (into {}
                (for [i (range n-seniors)]
                  [(str "s" i)
                   {:resolver-id (str "s" i)
                    :is-senior? true
                    :bond-amount (double senior-bond)
                    :utilization-factor (double util-factor)
                    :initial-bond (double senior-bond)
                    :coverage-used 0.0
                    :slash-history []
                    :epoch 0}]))
     
     :juniors (into {}
                (for [s (range n-seniors)
                      j (range n-juniors-per-senior)]
                  [(str "j" s "_" j)
                   {:resolver-id (str "j" s "_" j)
                    :is-senior? false
                    :initial-bond (double junior-bond)
                    :bond-remaining (double junior-bond)
                    :senior-delegation (str "s" s)
                    :is-exhausted? false
                    :slash-history []
                    :epoch 0}]))}))

(comment
  ;; Example usage:
  (let [params {:n-seniors 5
                :n-juniors-per-senior 10
                :senior-bond-amount 100000
                :junior-bond-amount 500
                :utilization-factor 0.5}
        
        {:keys [seniors juniors]} (initialize-waterfall-pool params)
        
        event {:resolver-id "j0_0"
               :senior-id "s0"
               :slash-amount 50
               :reason :fraud
               :epoch 1}]
    
    ;; Process single event
    (process-slash-event event juniors seniors)
    
    ;; Calculate metrics
    (aggregate-waterfall-metrics juniors seniors [event])))
