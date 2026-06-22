(ns resolver-sim.benchmark.cli
  (:require [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.benchmark.sharing :as sharing]
            [resolver-sim.benchmark.registry :as registry]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.evidence.timestamping :as ts]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.cli :refer [parse-opts]]))

(defn- load-index []
  (clojure.edn/read-string (slurp "BENCHMARKS.edn")))

(def cli-options
  [["-o" "--output PATH" "Output path for evidence bundle"
    :default "evidence/latest.edn"]
   ["-k" "--key PATH" "Path to private key for signing/attesting"]
   ["-p" "--password PASS" "Password for private key"]
   ["-v" "--verify" "Verify evidence bundle integrity and signature"]
   ["-H" "--hash-only" "Compute and print hash of an evidence bundle"]
   ["-l" "--list" "List available benchmarks in BENCHMARKS.edn"]
   [nil "--reproduce PATH" "Reproduce a benchmark run from evidence bundle"]
   [nil "--share-summary PATH" "Generate share summary for an evidence bundle"]
   [nil "--export PATH" "Export portable bundle (tar.gz) from evidence bundle"]
   [nil "--publish-ipfs PATH" "Publish exported bundle to IPFS"]
   [nil "--attest PATH" "Generate an independent attestation for an evidence bundle"]
   [nil "--verify-attestation PATH" "Verify an independent attestation"]
   [nil "--tsa-url URL" "RFC 3161 Time-Stamp Authority URL for timestamping evidence artifacts"]
   [nil "--history" "Display local evidence run history"]
   ["-h" "--help" "Show help"]])

(defn- interactive-ux [evidence output-path options]
  (println "\n" (apply str (repeat 40 "─")))
  (println "Benchmark completed successfully.")
  (println "\nEvidence Hash:\n" (:evidence/hash evidence))
  (println "\nSigned:\n" (if (:evidence/signature evidence) "yes" "no"))
  (println "\nNext Actions:")
  (println "[1] Export portable bundle (.tar.gz)")
  (println "[2] Generate share summary")
  (println "[3] Generate attestation")
  (println "[4] Publish to IPFS")
  (println "[5] Reproduce locally")
  (println "[q] Quit")
  (print "\nChoice > ")
  (flush)
  (let [choice (read-line)]
    (case choice
      "1" (sharing/export output-path (str output-path ".tar.gz"))
      "2" (println "\n" (sharing/share-summary evidence))
      "3" (if-let [key-path (:key options)]
            (let [att (sharing/attest output-path key-path (:password options))
                  att-path (str output-path ".attestation.edn")]
              (spit att-path (pr-str att))
              (println "Attestation written to:" att-path))
            (println "Private key path (-k) required for attestation."))
      "4" (let [tar-path (str output-path ".tar.gz")]
            (if (.exists (io/file tar-path))
              (sharing/publish-ipfs tar-path)
              (do (sharing/export output-path tar-path)
                  (sharing/publish-ipfs tar-path))))
      "5" (sharing/reproduce output-path)
      "q" (System/exit 0)
      (println "Invalid choice"))))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      errors
      (do (println errors) (System/exit 1))

      (:help options)
      (do (println summary) (System/exit 0))

      (:list options)
      (let [index (load-index)]
        (println (format "%-30s %s" "ID" "Description"))
        (println (apply str (repeat 60 "-")))
        (doseq [b (:benchmarks index)]
          (println (format "%-30s %s" (:id b) (:description b))))
        (System/exit 0))

      (:history options)
      (let [history (registry/get-history)]
        (println (format "%-30s %-20s %s" "Benchmark ID" "Date" "Outcome"))
        (println (apply str (repeat 70 "-")))
        (doseq [h history]
          (println (format "%-30s %-20s %s"
                           (get-in h [:benchmark :benchmark/id])
                           (java.util.Date. (:timestamp h))
                           (if (= (get-in h [:metrics :passed]) (get-in h [:metrics :total]))
                             "PASS" "FAIL"))))
        (System/exit 0))

      (:reproduce options)
      (System/exit (if (sharing/reproduce (:reproduce options)) 0 1))

      (:share-summary options)
      (let [bundle (edn/read-string (slurp (:share-summary options)))]
        (println (sharing/share-summary bundle))
        (System/exit 0))

      (:export options)
      (do (sharing/export (:export options) (str (:export options) ".tar.gz"))
          (System/exit 0))

      (:publish-ipfs options)
      (do (sharing/publish-ipfs (:publish-ipfs options))
          (System/exit 0))

      (:attest options)
      (if-let [key-path (:key options)]
        (let [att (sharing/attest (:attest options) key-path (:password options))
              path (str (:attest options) ".attestation.edn")]
          (spit path (pr-str att))
          (println "Attestation written to:" path)
          (System/exit 0))
        (do (println "Private key path (-k) required for attestation.")
            (System/exit 1)))

      (:verify-attestation options)
      (System/exit (if (sharing/verify-attestation (:verify-attestation options)) 0 1))

      (:verify options)
      (let [bundle-path (first arguments)
            bundle (edn/read-string (slurp bundle-path))
            hashable (dissoc bundle :timestamp :evidence/hash :evidence/signature)
            computed-hash (hc/domain-hash :bundle-root hashable)
            stored-hash (:evidence/hash bundle)
            hash-ok? (= computed-hash stored-hash)]
        (println "Verification for:" bundle-path)
        (println "Hash match:" (if hash-ok? "✓" "✗"))
        (when (:evidence/signature bundle)
          (if-let [pub-key-path (:evidence/public-key-path bundle)]
            (let [sig-ok? (signing/verify-signature stored-hash (:evidence/signature bundle) pub-key-path)]
              (println "Signature valid:" (if sig-ok? "✓" "✗")))
            (println "Public key path missing in bundle, cannot verify signature.")))
        (System/exit (if hash-ok? 0 1)))

      (:hash-only options)
      (let [bundle-path (first arguments)
            bundle (edn/read-string (slurp bundle-path))
            hashable (dissoc bundle :timestamp :evidence/hash :evidence/signature)
            computed-hash (hc/domain-hash :bundle-root hashable)]
        (println computed-hash)
        (System/exit 0))

      :else
      (binding [chain/*signing-key* (:key options)
                chain/*signing-password* (:password options)
                ts/*tsa-url* (:tsa-url options)]
        (let [arg (first arguments)
              index (load-index)
              benchmark-from-index (first (filter #(= (:id %) arg) (:benchmarks index)))
              manifest-path (cond
                              benchmark-from-index (:manifest benchmark-from-index)
                              (and arg (.endsWith arg ".edn")) arg
                              :else "benchmarks/dispute-liveness.edn")
              _ (println "Running benchmark:" manifest-path)
              evidence (runner/run-benchmark manifest-path)
              output-path (:output options)
              final-evidence (if-let [key-path (:key options)]
                               (let [sig (signing/sign-hash (:evidence/hash evidence) key-path (:password options))
                                     pub-path (str key-path ".pub")
                                     pub-exists? (.exists (io/file pub-path))]
                                 (assoc evidence
                                        :evidence/signature sig
                                        :evidence/public-key-path (if pub-exists? pub-path key-path)))
                               evidence)
              passed? (= (get-in evidence [:metrics :passed]) (get-in evidence [:metrics :total]))]
          (runner/write-evidence final-evidence output-path)
          (registry/record-entry final-evidence)
          (when passed?
            (interactive-ux final-evidence output-path options))
          (System/exit (if passed? 0 1)))))))
