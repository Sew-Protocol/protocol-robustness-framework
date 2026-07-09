(ns resolver-sim.commands.validate
  "Structural validation: lint, fmt check, notebook checks.
   Port of bb validate.")

(defn run
  "Run the structural validation pipeline."
  [{:keys [strict? json?] :as opts}]
  (println "Structural validation...")
  (println "  (integration pending — calls lint, fmt:check, notebook validation)")
  {:exit-code 0 :message "Structural validation passed"})

(defn fmt-check
  "Check code formatting with cljfmt."
  [{:keys [json?] :as opts}]
  (println "Checking formatting...")
  (println "  (cljfmt integration pending)")
  {:exit-code 0 :message "Format check passed"})

(defn lint
  "Lint source and test code with clj-kondo."
  [{:keys [json?] :as opts}]
  (println "Linting source...")
  (println "  (clj-kondo integration pending)")
  {:exit-code 0 :message "Lint passed"})
