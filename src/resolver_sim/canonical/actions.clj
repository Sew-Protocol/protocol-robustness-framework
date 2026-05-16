(ns resolver-sim.canonical.actions
  "DEPRECATED — moved to resolver-sim.protocols.sew.actions.
   This shim exists for backward compatibility only; do not add new usages.
   Update existing callers to require resolver-sim.protocols.sew.actions directly."
  (:require [resolver-sim.protocols.sew.actions :as sew-actions]))

(def action-map sew-actions/action-map)

(defn to-canonical [impl-action]
  (sew-actions/to-canonical impl-action))
