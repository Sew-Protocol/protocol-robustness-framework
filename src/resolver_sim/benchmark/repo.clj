(ns resolver-sim.benchmark.repo
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

(defn- git [& args]
  (let [{:keys [exit out err]} (apply sh "git" args)]
    (if (zero? exit)
      (str/trim out)
      nil)))

(defn- get-lockfile-hashes [root]
  (let [lockfiles ["deps.edn" "package-lock.json" "Cargo.lock" "requirements.txt"]]
    (into {}
          (keep (fn [f]
                  (let [path (str root "/" f)]
                    (when (git "ls-files" "--error-unmatch" f)
                      [f (git "hash-object" f)])))
                lockfiles))))

(defn metadata []
  (let [root    (git "rev-parse" "--show-toplevel")
        commit  (git "rev-parse" "HEAD")
        branch  (git "rev-parse" "--abbrev-ref" "HEAD")
        tag     (git "describe" "--tags" "--exact-match")
        dirty?  (not (str/blank? (git "status" "--short")))
        remotes (when-let [remote-out (git "remote" "-v")]
                  (->> (str/split-lines remote-out)
                       (map #(str/split % #"\s+"))
                       (map second)
                       distinct
                       vec))]
    {:repo
     {:root    root
      :commit  commit
      :branch  branch
      :tag     tag
      :dirty?  dirty?
      :remotes remotes
      :lockfiles (get-lockfile-hashes root)}}))
