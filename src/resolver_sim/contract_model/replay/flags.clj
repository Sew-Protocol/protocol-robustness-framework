(ns resolver-sim.contract-model.replay.flags
  "Replay capability flags — opt-in orchestration for theory, temporal, and validation.

   Defaults preserve existing Sew invariant-suite behaviour. Use
   `replay-yield-scenario` for yield-v1; `minimal-replay-flags` / `simple-replay`
   for other library-style scenarios.")

(def default-replay-flags
  "Full replay: invariants on, expectations on, strict validation, temporal from scenario."
  {:check-invariants?     true
   :evaluate-expectations? true
   :evaluate-theory?      nil
   :temporal-enabled?     nil
   :strict-validation?    true
   :metrics-profile       :sew-integrated})

(def minimal-replay-flags
  "Library-style replay: no temporal enforcement, no theory DSL, relaxed validation."
  (assoc default-replay-flags
         :evaluate-theory?      false
         :temporal-enabled?     false
         :strict-validation?    false
         :metrics-profile       :yield-provider))

(defn- flag-lookup
  [scenario replay-opts k default]
  (or (get-in replay-opts [:flags k])
      (get-in replay-opts [k])
      (get-in scenario [:options :flags k])
      (when (:minimal (or (get-in replay-opts [:minimal])
                          (get-in scenario [:options :minimal])))
        (get minimal-replay-flags k))
      default))

(defn resolve-replay-flags
  "Merge explicit `replay-opts`, scenario `:options`, and defaults.

   `:evaluate-theory?` / `:temporal-enabled?` nil means derive from scenario
   (theory block present; temporal-evidence :enabled?)."
  ([scenario] (resolve-replay-flags scenario {}))
  ([scenario replay-opts]
   (let [minimal? (boolean (or (:minimal replay-opts)
                               (get-in scenario [:options :minimal])
                               (= :minimal (get-in scenario [:options :profile]))))
         base     (if minimal? minimal-replay-flags default-replay-flags)
         theory-present? (boolean (:theory scenario))
         temporal-cfg    (:temporal-evidence scenario)
         temporal-default? (boolean (:enabled? temporal-cfg))]
     (merge base
            (select-keys (or (get-in scenario [:options :flags]) {}) (keys base))
            (select-keys (or (:flags replay-opts) {}) (keys base))
            {:evaluate-theory?
             (let [v (flag-lookup scenario replay-opts :evaluate-theory? nil)]
               (if (nil? v)
                 (and (not minimal?) theory-present?)
                 (boolean v)))
             :temporal-enabled?
             (let [v (flag-lookup scenario replay-opts :temporal-enabled? nil)]
               (if (nil? v) temporal-default? (boolean v)))
             :check-invariants?
             (boolean (flag-lookup scenario replay-opts :check-invariants? true))
             :evaluate-expectations?
             (boolean (flag-lookup scenario replay-opts :evaluate-expectations? true))
             :strict-validation?
             (boolean (flag-lookup scenario replay-opts :strict-validation?
                                   (not minimal?)))
             :metrics-profile
             (keyword (name (or (flag-lookup scenario replay-opts :metrics-profile
                                              (if minimal? :yield-provider :sew-integrated))
                                :sew-integrated)))}))))

(defn runner-opts-from-flags
  "Map replay flags to `scenario.runner` theory opts."
  [flags]
  {:evaluate-theory? (:evaluate-theory? flags)
   :require-theory?   false
   :strict-theory?    false})
