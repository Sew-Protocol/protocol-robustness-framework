(ns resolver-sim.evidence.aggregate
  "Generic evidence aggregate builder.
   Provides a consistent envelope for structured protocol evidence."
  (:require [resolver-sim.evidence.capture :as cap]
            [resolver-sim.time.context :as time-ctx]))

(defn evidence-content-hash
  "Compute the content-addressable hash of the evidence map inputs."
  [content]
  (cap/stable-hash content))

(defn extract-decision-frame
  "Extract the decision frame (world state metadata) from the world.
   The frame/id is derived from the world content hash for deterministic
   content-addressing — same world always produces the same frame."
  [world]
  {:frame/id (str "frame-" (cap/stable-hash world) "-" (:step world 0))
   :step (:step world 0)
   :block-ts (time-ctx/block-ts world)
   :world/hash (cap/stable-hash world)})

(defn build-evidence-aggregate
  "Construct a consistent evidence envelope.

   The aggregate owns the envelope schema and hashing,
   delegating the specific payload structure to domain constructors."
  [{:keys [evidence-type
           schema-version
           world
           frame
           subject
           inputs
           result
           context
           dependencies
           attribution]
    :or {dependencies []}}]
  (let [envelope
        {:evidence/type evidence-type
         :evidence/schema-version schema-version

         :evidence/frame frame
         :evidence/subject subject
         :evidence/context context
         :evidence/inputs inputs
         :evidence/result result
         :evidence/dependencies dependencies
         :evidence/attribution attribution}]
    (assoc envelope
           :evidence/hash-input
           {:frame frame
            :subject subject
            :inputs inputs
            :result result
            :context context})))
