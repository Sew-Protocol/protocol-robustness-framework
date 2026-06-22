(ns user
  (:require
   [clojure.repl :refer :all]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [clojure.tools.namespace.repl :as tn]
   [portal.api :as p]
   [dev.repl :as repl]
   [dev.explore :as explore]
   [dev.scenarios :as scenarios]
   [dev.artifacts :as artifacts]
   [dev.pro-rata :as pro-rata]
   [dev.tests :as tests]))

(defonce portal-instance (atom nil))

(defn portal
  []
  (when-not @portal-instance
    (reset! portal-instance (p/open))
    (add-tap #'p/submit))
  @portal-instance)

(defn close-portal
  []
  (when @portal-instance
    (remove-tap #'p/submit)
    (p/close)
    (reset! portal-instance nil)))

(defn reset
  []
  (tn/refresh :after 'user/after-refresh))

(defn reset-all
  []
  (tn/refresh-all :after 'user/after-refresh))

(defn after-refresh
  []
  (require 'dev.repl
           'dev.explore
           'dev.scenarios
           'dev.artifacts
           'dev.pro-rata
           'dev.tests
           :reload)
  :ready)

(defn ready
  []
  (portal)
  :repl/ready)

(comment
  ;; First command after connecting:
  (ready)

  ;; Reload changed namespaces:
  (reset)

  ;; Full reload:
  (reset-all)

  ;; Explore:
  (explore/find-project-ns "yield")
  (explore/find-project-var "pro-rata")
  (pro-rata/explain-generic-allocation {:amount 10
                                        :items [{:id :a :weight 1}
                                                {:id :b :weight 1}]})
  (scenarios/run-scenario :S103)
  (tests/run-tests-matching "partial-fill"))
