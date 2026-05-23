(ns resolver-sim.benchmark.adapter)

(defprotocol RepositoryAdapter
  (load-scenarios [this benchmark] "Load scenarios based on benchmark manifest")
  (execute-benchmark [this benchmark scenarios] "Execute scenarios and return results")
  (collect-metrics [this results] "Collect metrics from execution results"))
