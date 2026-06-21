(ns reader.ingest.tag-events
  "Tagging observability. `event` is the pure derivation of one tag-readable
   attempt's eval row; `record!` persists it and emits a Telemere signal. Shaped
   like reader.ingest.events: first-class columns for what we aggregate (outcome,
   model, tag count, timing) plus a jsonb `provenance` bag (the proposed labels +
   vocab size) for offline evals and local-model comparison."
  (:require [clojure.tools.logging :as log]
            [reader.db.crud :as crud]))

(defn event
  "Pure: summarize one tag-readable attempt into its eval row.

   `attempt` keys: :readable-type :readable-id (required), :outcome
   (:done|:failed|:skipped), :error-class (on failure), :model, :tag-count,
   :duration-ms, :provenance (a map)."
  [{:keys [readable-type readable-id outcome error-class model tag-count duration-ms provenance]}]
  {:readable-type readable-type
   :readable-id   readable-id
   :outcome       (name outcome)
   :error-class   (some-> error-class name)
   :model         model
   :tag-count     tag-count
   :duration-ms   duration-ms
   :provenance    (or provenance {})})

(defn record!
  "Persist one attempt's eval row (via `event`) and emit a structured signal.
   Returns the inserted row."
  [ds attempt]
  (let [ev (event attempt)]
    (log/info "tagging"
              (select-keys ev [:readable-type :outcome :error-class :model :tag-count :duration-ms]))
    (crud/create! ds :tagging-events ev)))
