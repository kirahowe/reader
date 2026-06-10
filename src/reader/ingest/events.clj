(ns reader.ingest.events
  "Extraction observability. `event` is the pure derivation of one attempt's
   eval row from its inputs/outputs; `record!` (added with the events table)
   persists it and emits a Telemere signal.

   The row is shaped for an eval dashboard: first-class columns for the things
   we aggregate (outcome, per-field provenance source, confidences, counts,
   timings) plus a jsonb `provenance` bag for the rest. Because every value
   carries the `:source` that produced it, the dashboard can show coverage *by
   source* — and when an LLM seam lands, its `:llm`-sourced fields show up in
   the same view with no schema change."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [reader.db.crud :as crud])
  (:import [java.net URI]))

(defn- domain-of [url]
  (some-> (try (.getHost (URI. url)) (catch Exception _ nil))
          (str/replace #"^www\." "")))

(defn- field-source [extract k]
  (some-> (get-in extract [:fields k :source]) name))

(defn event
  "Pure: summarize one extraction attempt into the eval row.

   `attempt` keys:
     :url         the ingested url (required)
     :outcome     :done | :failed (required)
     :error-class keyword, on failure
     :extract     the reader.ingest.extract context, on success
     :entities    the reader.ingest.schema/EntityResult, on success
     :durations   {:fetch-ms n :extract-ms n}"
  [{:keys [url outcome error-class extract entities durations]}]
  (let [body     (:body extract)
        coverage (when extract
                   (into {} (map (fn [k] [k (field-source extract k)]))
                         [:title :published-at :lang :canonical-url :site-name]))]
    {:url                url
     :domain             (domain-of url)
     :outcome            (name outcome)
     :error-class        (some-> error-class name)
     :extractor          (some-> (:extractor body) name)
     :word-count         (:word-count body)
     :body-confidence    (:confidence body)
     :entity-confidence  (:overall-confidence entities)
     :author-count       (when entities (count (:authors entities)))
     :title-source       (field-source extract :title)
     :author-source      (some-> (first (:authors entities)) :source name)
     :affiliation-source (some-> (:affiliation entities) :source name)
     :published-source   (field-source extract :published-at)
     :fetch-ms           (:fetch-ms durations)
     :extract-ms         (:extract-ms durations)
     :provenance         {:coverage     coverage
                          :body-signals (:signals body)}}))

(defn record!
  "Persist the eval row for one extraction attempt (via `event`) and emit a
   structured Telemere signal. The row feeds the admin eval dashboard; the
   signal feeds the prod log pipeline. Returns the inserted row."
  [ds attempt]
  (let [ev (event attempt)]
    (log/info "extraction"
              (select-keys ev [:url :domain :outcome :error-class :word-count
                               :body-confidence :entity-confidence :author-count
                               :author-source :affiliation-source]))
    (crud/create! ds :extraction-events ev)))
