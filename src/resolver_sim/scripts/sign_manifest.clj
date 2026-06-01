(ns resolver-sim.scripts.sign-manifest
  "CLI script: sign the focused (or latest) run manifest with an Ed25519 key.

  Usage:
    clojure -M:sign-manifest [key-path]

  Arguments:
    key-path  Path to the Ed25519 private key (OpenSSH or PKCS8).
              Defaults to 'test_key' in the project root.

  Writes signature.json to:
    - the run's own results/runs/<slug>-<run-id>/signature.json
    - results/test-artifacts/signature.json (latest symlink dir)"
  (:require [resolver-sim.notebooks.manifest.loader :as loader]
            [resolver-sim.notebooks.manifest.publication :as pub]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))

(def ^:private latest-dir "results/test-artifacts")

(defn- write-sig! [dir result]
  (let [f (io/file dir "signature.json")]
    (.mkdirs (io/file dir))
    (spit f (json/write-str result {:indent true}))
    (.getPath f)))

(defn -main [& args]
  (let [key-path (or (first args) "test_key")
        run      (loader/load-focused)
        manifest (:manifest run)]
    (when-not manifest
      (println "ERROR: No manifest found. Run bb scenario:run <scenario> first.")
      (System/exit 1))
    (println (str "Signing manifest: " (or (:run_id manifest) "unknown run-id")))
    (println (str "Key:              " key-path))
    (let [result (pub/sign-manifest manifest key-path)]
      (if (:error result)
        (do (println (str "ERROR: " (:error result)))
            (System/exit 1))
        (let [run-dir  (:dir run)
              paths    (cond-> [(write-sig! latest-dir result)]
                         (and run-dir (not= run-dir latest-dir))
                         (conj (write-sig! run-dir result)))]
          (println (str "Hash:             " (:hash result)))
          (println (str "Signature:        " (subs (:signature result) 0 16) "..."))
          (doseq [p paths]
            (println (str "Written:          " p))))))))
