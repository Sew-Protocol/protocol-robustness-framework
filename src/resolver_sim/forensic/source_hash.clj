(ns resolver-sim.forensic.source-hash
  "Source tree hashing with git and jj support.
   Detects the VCS automatically and computes commit, tree hash, and dirty flag.
   Used by bb hash:source and by pre-run commitment generation."
  (:require [clojure.java.shell :as sh])
  (:import [java.security MessageDigest]))

(defn- sh! [& cmd]
  (try (let [{:keys [exit out]} (apply sh/sh cmd)]
         (when (zero? exit) (clojure.string/trim out)))
       (catch Exception _ nil)))

(defn- sha256-hex [^bytes ba]
  (let [d (MessageDigest/getInstance "SHA-256")]
    (.update d ba)
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest d)))))

(defn- file-list-hash
  "Hash the sorted file list + sizes for a directory tree.
   Deterministic, no content reading. Picks git or jj tracked files."
  [root]
  (let [git-files (when-let [out (sh! "git" "-C" root "ls-files")]
                    (clojure.string/split-lines out))
        jj-files (when-let [out (sh! "jj" "file" "list")]
                   (clojure.string/split-lines out))
        files (or git-files jj-files [])
        sorted (sort files)
        lines (map (fn [f]
                     (let [path (str root "/" f)
                           file (java.io.File. path)]
                       (str f " " (.length file))))
                   (take 1000 sorted))]
    (when (seq lines)
      (sha256-hex (.getBytes (clojure.string/join "\n" lines) "UTF-8")))))

(defn source-hash
  "Detect VCS (prefer jj, fall back to git) and return source provenance map.
   Returns nil if no VCS is detected."
  []
  (let [jj-root (sh! "jj" "root")
        git-root (sh! "jj" "git" "rev-parse" "--show-toplevel")]
    (if jj-root
      (let [commit (sh! "jj" "log" "-r" "@" "--no-graph" "-T" "commit_id")
            change-id (sh! "jj" "log" "-r" "@" "--no-graph" "-T" "change_id.shortest(12)")
            dirty? (boolean (seq (sh! "jj" "status" "--short")))]
        {:vcs/type :jj
         :vcs/root jj-root
         :source/commit commit
         :source/change-id change-id
         :source/tree-hash (file-list-hash jj-root)
         :source/tree-hash-algorithm "ls-files-sha256"
         :source/dirty? dirty?})
      (when-let [commit (sh! "git" "rev-parse" "HEAD")]
        (let [root (sh! "git" "rev-parse" "--show-toplevel")]
          {:vcs/type :git
           :vcs/root root
           :source/commit commit
           :source/tree-hash (file-list-hash root)
           :source/tree-hash-algorithm "ls-files-sha256"
           :source/dirty? (boolean (seq (sh! "git" "status" "--porcelain")))})))))

(defn hash-source
  "CLI entry point: print source provenance as EDN.  Returns exit code."
  [& _]
  (let [result (source-hash)]
    (if result
      (do (println (pr-str result)) 0)
      (do (println "WARN: No VCS detected (not a git or jj repository).") 1))))
