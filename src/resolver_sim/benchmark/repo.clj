(ns resolver-sim.benchmark.repo
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [resolver-sim.vcs :as vcs]))

(defn- git [& args]
  (try
    (let [{:keys [exit out]} (apply sh "git" args)]
      (when (zero? exit)
        (some-> out str/trim not-empty)))
    (catch Exception _ nil)))

(defn- get-lockfile-hashes [root]
  (let [lockfiles ["deps.edn" "package-lock.json" "Cargo.lock" "requirements.txt"]]
    (into {}
          (keep (fn [f]
                  (let [path (str root "/" f)]
                    (when (git "ls-files" "--error-unmatch" f)
                      [f (git "hash-object" f)])))
                lockfiles))))

(defn metadata []
  (let [r       (vcs/root)
        tag     (git "describe" "--tags" "--exact-match")
        remotes (when-let [remote-out (git "remote" "-v")]
                  (->> (str/split-lines remote-out)
                       (map #(str/split % #"\s+"))
                       (map second)
                       distinct
                       vec))]
    {:repo
     {:root    r
      :commit  (vcs/commit-sha)
      :branch  (vcs/branch)
      :tag     tag
      :dirty?  (vcs/dirty?)
      :remotes remotes
      :lockfiles (get-lockfile-hashes r)}}))
