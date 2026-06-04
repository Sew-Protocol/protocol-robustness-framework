(ns resolver-sim.scripts.evidence
  "CLI wrapper: evidence bundle and signing helpers.

  Usage:
    clojure -M -m resolver-sim.scripts.evidence bundle <out-dir>
    clojure -M -m resolver-sim.scripts.evidence sign <private-key-path>

  The commands operate on the focused run (results/.notebook-focus) or the
  latest results/test-artifacts directory.
  "
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [resolver-sim.notebooks.manifest.loader :as loader]
            [resolver-sim.notebooks.manifest.bundle :as bundle]
            [resolver-sim.notebooks.manifest.publication :as pub]))

(defn- write-file! [dir name content]
  (let [f (io/file dir name)]
    (.mkdirs (io/file dir))
    (spit f (json/write-str content {:indent true}))
    (.getPath f)))

(defn -main [& args]
  (let [cmd (first args)
        param (second args)
        run  (loader/load-focused)
        manifest (:manifest run)
        registry (:registry run)
        out-dir (or param "results/evidence-bundle")]
    (when-not manifest
      (println "ERROR: no manifest found. Run bb scenario:run first.")
      (System/exit 1))
    (case cmd
      "bundle"
      (let [res (bundle/export-bundle! manifest registry out-dir)]
        (if (:error res)
          (do (println "ERROR:" (:error res)) (System/exit 1))
          (do (println "Wrote bundle to:" (:out-dir res)) (println "Bundle hash:" (:bundle-hash res)))))

      "sign"
      (let [key-path param]
        (when (or (nil? registry) (empty? (:artifacts registry)))
          (println "ERROR: missing artifact registry; run artifacts:index or evidence:build first.")
          (System/exit 1))
        (let [result (pub/sign-manifest manifest key-path :registry registry)]
          (if (:error result)
            (do (println "ERROR:" (:error result)) (System/exit 1))
            (let [signed-manifest (:manifest result)
                  latest-dir "results/test-artifacts"
                  run-dir (:dir run)]
              ;; write updated manifest-with-registry into latest and run dirs
              (spit (io/file latest-dir "test-run.json") (json/write-str signed-manifest {:indent true}))
              (when run-dir
                (spit (io/file run-dir "test-run.json") (json/write-str signed-manifest {:indent true})))
              (println "Signed manifest hash:" (:hash result))
              (println "Signature (prefix):" (subs (:signature result) 0 16))))))

      (do (println "Unknown command. Use 'bundle <out-dir>' or 'sign <key-path>'.") (System/exit 1)))))