(ns resolver-sim.benchmark.sharing
  (:require [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.benchmark.hashing :as hashing]
            [resolver-sim.benchmark.repo :as repo]
            [resolver-sim.benchmark.signing :as signing]
            [clojure.java.shell :refer [sh]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn generate-reproduce-command [evidence-path]
  (str "bb benchmark:reproduce " evidence-path))

(defn share-summary [evidence]
  (let [bm-id (get-in evidence [:benchmark :benchmark/id])
        protocol-commit (get-in evidence [:repo :repo :commit])
        outcome (if (= (get-in evidence [:metrics :passed]) (get-in evidence [:metrics :total]))
                  "PASS" "FAIL")
        evidence-hash (:evidence/hash evidence)
        signed? (contains? evidence :evidence/signature)]
    (str "Benchmark:\n" bm-id "\n\n"
         "Protocol Commit:\n" protocol-commit "\n\n"
         "Result:\n" outcome "\n\n"
         "Evidence Hash:\n" evidence-hash "\n\n"
         "Signed:\n" (if signed? "yes" "no") "\n\n"
         "Reproduce:\n" (generate-reproduce-command "evidence/latest.edn"))))

(defn reproduce [evidence-path]
  (let [evidence (edn/read-string (slurp evidence-path))
        target-commit (get-in evidence [:repo :repo :commit])
        current-meta (repo/metadata)
        current-commit (get-in current-meta [:repo :commit])
        manifest-path (get-in evidence [:benchmark :manifest])]
    (if (not= target-commit current-commit)
      (do
        (println "Warning: Repository commit mismatch.")
        (println "Target commit: " target-commit)
        (println "Current commit: " current-commit))
      (println "Repository state matches."))
    
    (println "Rerunning benchmark...")
    ;; We need to make sure we use the same manifest. 
    ;; Manifest might have changed, so ideally we'd use the one in evidence.
    ;; But for now let's use manifest-path if available.
    (let [new-evidence (runner/run-benchmark (or manifest-path "benchmarks/dispute-liveness.edn"))
          new-hash (:evidence/hash new-evidence)
          old-hash (:evidence/hash evidence)]
      (println "Recomputed Hash: " new-hash)
      (println "Original Hash:   " old-hash)
      (if (= new-hash old-hash)
        (do (println "✓ Hash match! Results are reproducible.") true)
        (do (println "✗ Hash mismatch! Results differ from original run.") false)))))

(defn export [evidence-path export-tar-path]
  (let [evidence (edn/read-string (slurp evidence-path))
        tmp-dir (io/file "tmp-export")
        _ (.mkdirs tmp-dir)]
    (try
      (spit (io/file tmp-dir "evidence.edn") (pr-str evidence))
      (spit (io/file tmp-dir "manifest.edn") (pr-str (:benchmark evidence)))
      (spit (io/file tmp-dir "repo.edn") (pr-str (:repo evidence)))
      (spit (io/file tmp-dir "results.edn") (pr-str (:results evidence)))
      (spit (io/file tmp-dir "metrics.edn") (pr-str (:metrics evidence)))
      
      ;; Deterministic tar
      ;; We use --sort=name --mtime='2026-01-01' --owner=0 --group=0 --numeric-owner
      (let [{:keys [exit out err]} (sh "tar" "--sort=name" "--mtime=2026-01-01" "--owner=0" "--group=0" "--numeric-owner" "-czf" export-tar-path "-C" "tmp-export" ".")]
        (if (zero? exit)
          (println "Portable bundle exported to:" export-tar-path)
          (println "Export failed:" err)))
      (finally
        (sh "rm" "-rf" "tmp-export")))))

(defn publish-ipfs [export-tar-path]
  (let [{:keys [exit out err]} (sh "ipfs" "add" "-Q" export-tar-path)]
    (if (zero? exit)
      (let [cid (str/trim out)
            manifest {:ipfs-cid cid
                      :timestamp (System/currentTimeMillis)
                      :bundle-path export-tar-path}]
        (spit "evidence-manifest.json" (json/write-str manifest {:key-fn name :indent true}))
        (println "Published to IPFS")
        (println "\nCID:\n" cid)
        (println "\nGateway:\n" (str "https://ipfs.io/ipfs/" cid))
        cid)
      (do
        (println "IPFS publication failed (is ipfs installed and daemon running?)")
        nil))))

(defn attest [evidence-path private-key-path password]
  (let [evidence (edn/read-string (slurp evidence-path))

        bm-id (get-in evidence [:benchmark :benchmark/id])
        bm-commit (get-in evidence [:benchmark :commit])
        protocol-commit (get-in evidence [:repo :repo :commit])
        evidence-hash (:evidence/hash evidence)
        signature (signing/sign-hash evidence-hash private-key-path password)
        attestation {:benchmark/id bm-id
                     :benchmark/commit bm-commit
                     :protocol/commit protocol-commit
                     :evidence/hash evidence-hash
                     :attestor {:public-key-path (str private-key-path ".pub")}
                     :signature signature}]
    (println "Attestation generated.")
    attestation))

(defn verify-attestation [attestation-path]
  (let [attestation (edn/read-string (slurp attestation-path))
        evidence-hash (:evidence/hash attestation)
        signature (:signature attestation)
        pub-key-path (get-in attestation [:attestor :public-key-path])]
    (if (and evidence-hash signature pub-key-path)
      (let [sig-ok? (signing/verify-signature evidence-hash signature pub-key-path)]
        (println "Attestation Verification:")
        (println "Benchmark ID:    " (:benchmark/id attestation))
        (println "Evidence Hash:   " evidence-hash)
        (println "Signature valid: " (if sig-ok? "✓" "✗"))
        sig-ok?)
      (do
        (println "Invalid attestation format.")
        false))))
