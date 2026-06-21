(ns dev.repl
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]))

(defn pp
  [x]
  (pprint x)
  x)

(defn spy
  ([x]
   (tap> x)
   x)
  ([label x]
   (tap> {:label label :value x})
   x))

(defn keys+
  [m]
  (-> m keys sort vec))

(defn select-keys+
  [m ks]
  (select-keys m ks))

(defn summarize-map
  [m]
  {:type      (type m)
   :count     (when (counted? m) (count m))
   :keys      (when (map? m) (-> m keys sort vec))
   :sample    (cond
                (map? m) (into {} (take 10 m))
                (sequential? m) (take 10 m)
                :else m)})

(defn tap
  [x]
  (tap> x)
  x)

(defn tap-summary
  [x]
  (tap> (summarize-map x))
  x)
