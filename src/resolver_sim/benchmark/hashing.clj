(ns resolver-sim.benchmark.hashing
  {:deprecated "1.0.0"
   :superseded-by "resolver-sim.hash.canonical"
   :status :deleted
   :message "REMOVED — all callers migrated to resolver-sim.hash.canonical.
             This namespace is retained only to prevent broken requires
             during incremental builds. All functions were removed in
             the canonical hash intent migration. No code should import
             this namespace."}
  (:require [clojure.walk :as walk]
            [clojure.edn :as edn])
  (:import [java.security MessageDigest]))

(defn hash-evidence
  {:deprecated "1.0.0" :superseded-by "resolver-sim.hash.canonical/hash-with-intent"}
  [& args]
  (throw (ex-info "resolver-sim.benchmark.hashing namespace is removed"
                  {:function "hash-evidence"
                   :migration "Use (hc/hash-with-intent {:hash/intent ...} data)"
                   :ns "resolver-sim.hash.canonical"})))

(defn stable-hash
  {:deprecated "1.0.0" :superseded-by "resolver-sim.hash.canonical/hash-with-intent"}
  [& args]
  (throw (ex-info "resolver-sim.benchmark.hashing namespace is removed"
                  {:function "stable-hash"
                   :migration "Use (hc/hash-with-intent {:hash/intent ...} data)"
                   :ns "resolver-sim.hash.canonical"})))

(defn stable-hash-prefixed
  {:deprecated "1.0.0" :superseded-by "resolver-sim.hash.canonical/hash-with-intent"}
  [& args]
  (throw (ex-info "resolver-sim.benchmark.hashing namespace is removed"
                  {:function "stable-hash-prefixed"
                   :migration "Use (hc/hash-with-intent {:hash/intent ...} data)"
                   :ns "resolver-sim.hash.canonical"})))
