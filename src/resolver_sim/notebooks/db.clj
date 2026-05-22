(ns resolver-sim.notebooks.db)

(defn ds-result []
  (try
    (require '[evaluation.xtdb])
    {:ok? true
     :ds ((resolve 'evaluation.xtdb/->datasource))
     :source "evaluation.xtdb/->datasource"}
    (catch Throwable e
      {:ok? false
       :error (or (.getMessage e) (str e))
       :source "evaluation.xtdb/->datasource"})))

(defn query [sqlvec]
  (let [{:keys [ok? ds] :as dsr} (ds-result)]
    (if-not ok?
      (merge dsr {:rows [] :sql sqlvec})
      (try
        (require '[next.jdbc])
        {:ok? true :rows ((resolve 'next.jdbc/execute!) ds sqlvec) :sql sqlvec}
        (catch Throwable e
          {:ok? false :rows [] :error (or (.getMessage e) (str e)) :sql sqlvec})))))

(defn query-on [ds sqlvec]
  (try
    (require '[next.jdbc])
    {:ok? true :rows ((resolve 'next.jdbc/execute!) ds sqlvec) :sql sqlvec}
    (catch Throwable e
      {:ok? false :rows [] :error (or (.getMessage e) (str e)) :sql sqlvec})))
