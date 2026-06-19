(ns resolver-sim.demo.spec
  "Demo spec schema and validation.

   A Demo Spec is an EDN file under demos/<id>/demo.edn that defines
   what the demo does, what commands to run, and how to interpret results.
   This namespace provides validation and accessor helpers."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ── Required keys ───────────────────────────────────────────────────────────

(def required-top-level
  #{:demo/id :demo/title :demo/audience :demo/version
    :scenario :sections})

(def valid-audiences
  #{:researchers :executive :protocol-engineer :audit})

(def valid-output-formats
  #{:markdown :html :pdf})

;; ── Validation ──────────────────────────────────────────────────────────────

(defn validate
  "Validate a demo spec map. Returns {:valid true} or {:valid false :errors [...]}."
  [spec]
  (let [errors (atom [])]
    ;; Check required top-level keys
    (doseq [k required-top-level]
      (when (nil? (get spec k))
        (swap! errors conj (str "Missing required key: " k))))
    ;; :demo/id must be a keyword
    (when (and (:demo/id spec) (not (keyword? (:demo/id spec))))
      (swap! errors conj ":demo/id must be a keyword"))
    ;; :demo/audience must be valid
    (when-let [aud (:demo/audience spec)]
      (when-not (valid-audiences aud)
        (swap! errors conj (str "Invalid :demo/audience: " aud
                                " — must be one of " valid-audiences))))
    ;; :scenario must have :id
    (when-let [sc (:scenario spec)]
      (when-not (:id sc)
        (swap! errors conj ":scenario must have an :id"))
      (when-not (:command sc)
        (swap! errors conj ":scenario must have a :command")))
    ;; :sections must be a vector
    (when-let [sections (:sections spec)]
      (when-not (vector? sections)
        (swap! errors conj ":sections must be a vector"))
      (doseq [s sections]
        (when-not (:id s)
          (swap! errors conj "Each section must have an :id"))
        (when-not (:title s)
          (swap! errors conj "Each section must have a :title"))))
    ;; :outputs format validation
    (when-let [outputs (:outputs spec)]
      (when-let [fmt (:format outputs)]
        (when-not (every? valid-output-formats fmt)
          (swap! errors conj (str "Invalid :outputs/:format — must be subset of "
                                  valid-output-formats)))))
    (if (empty? @errors)
      {:valid true}
      {:valid false :errors @errors})))

;; ── Loading ─────────────────────────────────────────────────────────────────

(defn load-spec
  "Load a demo spec from a directory path.
   Expects <dir>/demo.edn. Returns parsed map or nil."
  [dir]
  (let [f (io/file dir "demo.edn")]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(defn find-spec
  "Find and load a demo spec by ID or path.
   - Keyword: looks in demos/<name>/demo.edn
   - String without path separators: looks in demos/<name>/demo.edn
   - String with / or .: treated as a literal path to a demo.edn or directory"
  [id]
  (let [id-str (if (keyword? id) (name id) id)
        demo-dir (if (or (keyword? id)
                         (not (or (.contains id-str "/")
                                  (.contains id-str "."))))
                   (str "demos/" id-str)
                   id-str)]
    (load-spec demo-dir)))

;; ── Accessors ───────────────────────────────────────────────────────────────

(defn demo-id [spec] (:demo/id spec))
(defn demo-title [spec] (:demo/title spec))
(defn scenario-command [spec] (get-in spec [:scenario :command]))
(defn sections [spec] (:sections spec))
