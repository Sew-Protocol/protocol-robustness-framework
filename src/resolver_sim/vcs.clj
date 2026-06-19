(ns resolver-sim.vcs
  "VCS-agnostic wrapper supporting jj (Jujutsu) and Git.
   Tries jj first, falls back to git, returns nil when neither is available."
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

(defn- run
  "Run a command, return trimmed stdout on success, nil otherwise."
  [cmd & args]
  (try
    (let [{:keys [exit out]} (apply sh cmd args)]
      (when (zero? exit)
        (some-> out str/trim not-empty)))
    (catch Exception _ nil)))

(defn commit-sha
  "Full commit SHA from jj or git HEAD."
  []
  (or (run "jj" "log" "-r" "@" "--no-graph" "-T" "commit_id")
      (run "git" "rev-parse" "HEAD")))

(defn short-sha
  "Short commit SHA (12 chars) from jj or git HEAD."
  []
  (or (run "jj" "log" "-r" "@" "--no-graph" "-T" "commit_id.shortest(12)")
      (run "git" "rev-parse" "--short" "HEAD")))

(defn commit-message
  "First line of current commit message from jj or git."
  []
  (or (some-> (run "jj" "log" "-r" "@" "--no-graph" "-T" "description")
              str/split-lines
              first)
      (run "git" "log" "-1" "--format=%s")))

(defn branch
  "Active bookmark (jj) or branch (git) name.
   For jj, returns bookmarks on the current change, then falls back to
   the change ID prefix, then git branch."
  []
  (or (some-> (run "jj" "bookmark" "list" "-r" "@" "-T" "name")
              str/split-lines
              (->> (remove str/blank?))
              first)
      (some-> (run "jj" "log" "-r" "@" "--no-graph" "-T" "change_id.shortest(8)")
              not-empty)
      (run "git" "rev-parse" "--abbrev-ref" "HEAD")))

(defn root
  "Repository root directory."
  []
  (or (run "jj" "root")
      (run "git" "rev-parse" "--show-toplevel")))

(defn dirty?
  "True when the working copy has uncommitted changes."
  []
  (let [dirty-str (or (run "jj" "status" "--color" "never")
                      (run "git" "status" "--short"))]
    (and dirty-str (not (str/includes? dirty-str "The working copy is clean")))))

(defn remotes
  "List of remote URLs."
  []
  (when-let [r (run "git" "remote" "-v")]
    (->> (str/split-lines r)
         (map #(second (str/split % #"\s+")))
         distinct
         vec)))

(defn metadata
  "Full repo metadata map compatible with benchmark/repo format."
  []
  (let [r (root)]
    {:repo
     {:root    r
      :commit  (commit-sha)
      :branch  (branch)
      :dirty?  (dirty?)
      :remotes (remotes)}}))
