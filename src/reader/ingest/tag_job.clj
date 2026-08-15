(ns reader.ingest.tag-job
  "The tag-readable job: the write edge that turns a finalized readable into
   tags. Loads the readable's content, runs the infer-tags abstraction
   (reader.ingest.tag), embeds + dedups the proposed labels into the shared
   vocabulary (reader.domain.tags), writes the baseline plus the readable's own
   embedding, and records an eval event. The infer-tags fn and the embed fn are
   injected (the LLM/cloud today, a local model tomorrow); the pure pieces live
   in the namespaces it wires together."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [reader.ai :as ai]
            [reader.db.crud :as crud]
            [reader.domain.tags :as tags]
            [reader.ingest.tag :as tag]
            [reader.ingest.tag-events :as events]
            [reader.jobs :as jobs])
  (:import (java.time Instant)))

(def ^:private type->table
  {"article" :articles "paper" :papers "newsletter_issue" :newsletter-issues})

(defn- strip-html [s]
  (some-> s (str/replace #"(?s)<[^>]+>" " ") (str/replace #"\s+" " ") str/trim not-empty))

(defn content-for
  "The model signal for (readable-type, readable-id): {:title :abstract :text},
   or nil when the readable is gone. `text` is the body with markup stripped."
  [ds readable-type readable-id]
  (when-let [table (type->table readable-type)]
    (when-let [row (crud/by-id ds table readable-id)]
      (let [q (name table)]
        {:title    (or (get row (keyword q "title")) (get row (keyword q "subject")))
         :abstract (get row (keyword q "abstract"))
         :text     (strip-html (get row (keyword q "body-html")))}))))

(defn- doc-text
  "The text embedded to represent the whole readable (for phase-2 similarity)."
  [{:keys [title abstract text]}]
  (let [s (->> [title abstract text] (remove nil?) (str/join ". "))]
    (subs s 0 (min (count s) 2000))))

(defn- require-content
  "The readable's model signal, or a fatal error when it's gone — a missing
   readable can't be retried into existence."
  [ds readable-type readable-id]
  (or (content-for ds readable-type readable-id)
      (throw (ex-info "readable not found for tagging"
                      {:error-class :missing-readable :fatal? true}))))

(defn- content-version-current?
  "Newsletter tag jobs carry the extraction version they describe. With `lock?`,
   take a shared row lock so reprocessing cannot change the content between the
   version check and tag writes. Other readable types are currently unversioned."
  [ds readable-type readable-id content-version lock?]
  (if (or (nil? content-version) (not= "newsletter_issue" readable-type))
    true
    (let [query (cond-> {:select [:extraction-version]
                         :from [:newsletter-issues]
                         :where [:= :id readable-id]}
                  lock? (assoc :for [:share]))
          row   (jdbc/execute-one! ds (sql/format query) crud/opts)]
      ;; A missing row still flows through require-content's fatal error path.
      (or (nil? row)
          (= (long content-version) (:newsletter-issues/extraction-version row))))))

(defn- coerce+validate
  "Coerce the (possibly remote) tagger's raw output to the tag schema and assert
   its contract, returning the result or throwing a fatal violation."
  [raw]
  (let [result (tag/coerce raw)]
    (when-not (tag/valid? result)
      (throw (ex-info "infer-tags violated its contract"
                      {:error-class :invalid-tags :fatal? true
                       :explain     (tag/explain result)})))
    result))

(defn- elapsed-ms [t0]
  (quot (- (System/nanoTime) t0) 1000000))

(defn- record-failure!
  "Record a :failed eval event for a tagging error. Its own insert must never
   mask the real error, so a recording failure is logged and swallowed (mirrors
   reader.ingest/extract-article!)."
  [ds readable-type readable-id t]
  (try
    (events/record! ds {:readable-type readable-type
                        :readable-id   readable-id
                        :outcome       :failed
                        :error-class   (or (:error-class (ex-data t)) :unknown)})
    (catch Throwable e
      (log/error e "failed to record tagging failure event"
                 {:readable-type readable-type :readable-id readable-id}))))

(defn tag-readable!
  "Job handler: infer + persist tags for a readable, plus its embedding and an
   eval event. Deps: {:infer-tags fn, :embed fn, :threshold, :embed-model}.
   Idempotent — `set-baseline!` and the embedding upsert replace any prior
   result, so a re-run (or a re-finalized readable) re-tags cleanly. Embeddings
   are computed before the transaction (network must not hold a tx open); the
   missing-readable and contract-violation cases are fatal (no retry). Payload
   ids arrive as strings (jsonb drops uuid types)."
  [ds {:keys [readable-type readable-id content-version]}
   {:keys [infer-tags embed threshold embed-model]}]
  (let [readable-id (parse-uuid (str readable-id))]
    (try
      (if-not (content-version-current? ds readable-type readable-id content-version false)
        {:stale? true}
        (let [t0        (System/nanoTime)
              content   (require-content ds readable-type readable-id)
              existing  (mapv :label (tags/vocabulary ds))
              result    (coerce+validate (infer-tags content existing))
              proposals (:tags result)
              ;; One embed call: the readable doc first, then each proposed label.
              ;; A provider returning the wrong batch size would silently misalign
              ;; labels to vectors, so verify the count before zipping them.
              vectors   (embed (cons (doc-text content) (map :label proposals)))
              _         (when (not= (count vectors) (inc (count proposals)))
                          (throw (ex-info "embedding count did not match inputs"
                                          {:error-class :embedding-count-mismatch
                                           :expected (inc (count proposals))
                                           :got (count vectors)})))
              [doc-vec & label-vecs] vectors
              duration  (elapsed-ms t0)]
          (jdbc/with-transaction [tx ds]
            (if-not (content-version-current? tx readable-type readable-id content-version true)
              {:stale? true}
              (let [entries (tags/resolve-tags! tx threshold
                                                (map (fn [p v] {:label (:label p) :embedding v})
                                                     proposals label-vecs))]
                (tags/set-baseline! tx readable-type readable-id
                                    (map (fn [entry p] {:tag-id (:id entry) :confidence (:confidence p)})
                                         entries proposals))
                (tags/set-readable-embedding! tx readable-type readable-id doc-vec embed-model)
                (events/record! tx {:readable-type readable-type
                                    :readable-id   readable-id
                                    :outcome       :done
                                    :model         (:model result)
                                    :tag-count     (count proposals)
                                    :duration-ms   duration
                                    :provenance    {:labels     (mapv :label proposals)
                                                    :vocab-size (count existing)}})
                result)))))
      (catch Throwable t
        (record-failure! ds readable-type readable-id t)
        (throw t)))))

(defn skip-readable!
  "No real model is configured: record a :skipped eval event and re-enqueue the
   job for a later attempt, so the readable is tagged once the model secrets land
   — without writing stub tags/embeddings into the *shared* baseline or burning
   the retry budget. Stale versioned jobs complete without rescheduling. Bounded:
   a readable always has exactly one pending current-version tag-readable job."
  [ds {:keys [readable-type readable-id] :as payload} retry-secs]
  (let [readable-id (parse-uuid (str readable-id))]
    (if-not (content-version-current? ds readable-type readable-id (:content-version payload) false)
      {:stale? true}
      (do
        (events/record! ds {:readable-type readable-type
                            :readable-id   readable-id
                            :outcome       :skipped})
        (jobs/enqueue! ds "tag-readable" payload
                       {:run-at (.plusSeconds (Instant/now) retry-secs)})))))

(defmethod ig/init-key :reader.ingest.tag-job/handler
  ;; `require-model?` (set in prod) makes the job skip + reschedule when no real
  ;; tagger + embedder is wired, rather than fall back to the stubs — stub tags
  ;; are fine in dev's throwaway DB but must not pollute the shared prod baseline.
  ;; Dev/test leave it unset, so the stubs run the pipeline offline as before.
  [_ {:keys [tagger embed threshold embed-model require-model? skip-retry-secs]
      :or   {threshold tags/default-threshold skip-retry-secs 300}}]
  (let [live?    (and (some? tagger) (some? embed))
        infer    (or tagger (tag/stub-tagger))
        embed-fn (or embed (ai/stub-embed))]
    (fn [ds payload]
      (if (and require-model? (not live?))
        (skip-readable! ds payload skip-retry-secs)
        (tag-readable! ds payload {:infer-tags  infer
                                   :embed       embed-fn
                                   :threshold   threshold
                                   :embed-model embed-model})))))
