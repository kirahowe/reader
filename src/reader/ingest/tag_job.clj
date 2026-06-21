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

(defn tag-readable!
  "Job handler: infer + persist tags for a readable, plus its embedding and an
   eval event. Deps: {:infer-tags fn, :embed fn, :threshold, :embed-model}.
   Idempotent — `set-baseline!` and the embedding upsert replace any prior
   result, so a re-run (or a re-finalized readable) re-tags cleanly. Embeddings
   are computed before the transaction (network must not hold a tx open); the
   missing-readable and contract-violation cases are fatal (no retry). Payload
   ids arrive as strings (jsonb drops uuid types)."
  [ds {:keys [readable-type readable-id]} {:keys [infer-tags embed threshold embed-model]}]
  (let [readable-id (parse-uuid (str readable-id))
        t0          (System/nanoTime)]
    (try
      (let [content (content-for ds readable-type readable-id)]
        (when-not content
          (throw (ex-info "readable not found for tagging"
                          {:error-class :missing-readable :fatal? true})))
        (let [existing  (mapv :label (tags/vocabulary ds))
              result    (tag/coerce (infer-tags content existing))
              _         (when-not (tag/valid? result)
                          (throw (ex-info "infer-tags violated its contract"
                                          {:error-class :invalid-tags :fatal? true
                                           :explain     (tag/explain result)})))
              proposals (:tags result)
              ;; One embed call: the readable doc first, then each proposed label.
              vectors   (embed (cons (doc-text content) (map :label proposals)))
              doc-vec   (first vectors)
              label-vec (rest vectors)
              duration  (long (/ (- (System/nanoTime) t0) 1000000))]
          (jdbc/with-transaction [tx ds]
            (let [entries     (tags/resolve-tags! tx threshold
                                                  (map (fn [p e] {:label (:label p) :embedding e})
                                                       proposals label-vec))
                  assignments (map (fn [entry p] {:tag-id (:id entry) :confidence (:confidence p)})
                                   entries proposals)]
              (tags/set-baseline! tx readable-type readable-id assignments)
              (tags/set-readable-embedding! tx readable-type readable-id doc-vec embed-model)
              (events/record! tx {:readable-type readable-type
                                  :readable-id   readable-id
                                  :outcome       :done
                                  :model         (:model result)
                                  :tag-count     (count assignments)
                                  :duration-ms   duration
                                  :provenance    {:labels    (mapv :label proposals)
                                                  :vocab-size (count existing)}})
              result))))
      (catch Throwable t
        ;; The failure event isn't tied to the rolled-back write, but its own
        ;; insert must never mask the real error — log and swallow a recording
        ;; failure (mirrors reader.ingest/extract-article!).
        (try
          (events/record! ds {:readable-type readable-type
                              :readable-id   readable-id
                              :outcome       :failed
                              :error-class   (or (:error-class (ex-data t)) :unknown)})
          (catch Throwable e
            (log/error e "failed to record tagging failure event"
                       {:readable-type readable-type :readable-id readable-id})))
        (throw t)))))

(defn skip-readable!
  "No real model is configured: record a :skipped eval event and re-enqueue the
   job for a later attempt, so the readable is tagged once the model secrets land
   — without writing stub tags/embeddings into the *shared* baseline or burning
   the retry budget. Bounded: a readable always has exactly one pending
   tag-readable job (this one completes as it schedules the next)."
  [ds {:keys [readable-type readable-id] :as payload} retry-secs]
  (events/record! ds {:readable-type readable-type
                      :readable-id   (parse-uuid (str readable-id))
                      :outcome       :skipped})
  (jobs/enqueue! ds "tag-readable" payload {:run-at (.plusSeconds (Instant/now) retry-secs)}))

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
