(ns test-scenario-lookup
  (:(ns test-scenario-lookup)require [dev.scenarios :as scenarios]
            [resolver-sim.protocols.sew.invariant-scenarios :as inv-sc]))

;; Test the scenario lookup functionality
(println "=== Testing Scenario Lookup ===")

;; 1. Test list-scenarios function
(println "\n1. Listing all scenarios:")
(try
  (doseq [scenario (take 5 (scenarios/list-scenarios))]
    (println "  " scenario))
  (catch Exception e
    (println "Error:" (.getMessage e))))

;; 2. Test finding S01 scenarios
(println "\n2. Finding S01 scenarios:")
(try
  (doseq [scenario (scenarios/list-scenarios "01")]
    (println "  " scenario))
  (catch Exception e
    (println "Error:" (.getMessage e))))

;; 3. Test finding S103 scenarios
(println "\n3. Finding S103 scenarios:")
(try
  (doseq [scenario (scenarios/list-scenarios "103")]
    (println "  " scenario))
  (catch Exception e
    (println "Error:" (.getMessage e))))

;; 4. Test direct registry access
(println "\n4. Direct registry check for S01:")
(try
  (doseq [[name _] @inv-sc/all-scenarios]
    (when (re-find #"S01" name)
      (println "  Found:" name)))
  (catch Exception e
    (println "Error:" (.getMessage e))))

;; 5. Test find-scenario function
(println "\n5. Testing find-scenario:")
(try
  (when-let [scenario (scenarios/find-scenario :S01)]
    (println "  Found S01 scenario:" (keys scenario)))
  (catch Exception e
    (println "Error:" (.getMessage e))))

(println "\n=== Test Complete ===")
