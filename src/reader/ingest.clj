(ns reader.ingest
  "Orchestrates turning an external source into a stored, queued readable. The
   pure pieces live in sibling namespaces (reader.ingest.fetch / .extract /
   .entities / .events); this namespace wires them to the database.

   `persist!` is the write edge — it finalizes a placeholder article from an
   extraction. Author/affiliation extraction goes through the swappable entity
   abstraction (reader.ingest.entities today, an LLM-backed implementation tomorrow)."
  (:require [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [reader.domain.affiliations :as affiliations]
            [reader.domain.articles :as articles]
            [reader.domain.authors :as authors]
            [reader.db.crud :as crud]
            [reader.ingest.email :as email]
            [reader.ingest.entities :as entities]
            [reader.ingest.events :as events]
            [reader.ingest.extract :as extract]
            [reader.ingest.fetch :as fetch]
            [reader.ingest.schema :as schema]
            [reader.jobs :as jobs]
            [reader.domain.newsletters :as newsletters]
            [reader.domain.reading :as reading]
            [reader.util.slug :as slug]
            [reader.storage :as storage]
            [reader.util.url :as url])
  (:import [java.time Instant]))

(defn- clear-authorships! [tx readable-type readable-id]
  (jdbc/execute! tx (sql/format {:delete-from :authorships
                                 :where       [:and
                                               [:= :readable-type readable-type]
                                               [:= :readable-id readable-id]]})))

(defn- finalize!
  "The write body of `persist!`, scoped to an existing transaction `tx`: resolve
   & set the affiliation, write the extracted fields/body, and (re)attach
   authorships in byline order. The authorship inserts are safe without
   authorships/attach!'s own existence check + lock: the article row is locked
   by our UPDATE in this same tx. Returns the finalized article row."
  [tx article-id extract entities]
  (let [aff   (some->> (:affiliation entities) :name (affiliations/find-or-create! tx))
        attrs (articles/ingest-attrs extract (:affiliations/id aff) (Instant/now))]
    (crud/update! tx :articles article-id attrs)
    (clear-authorships! tx "article" article-id)
    (doseq [[i a] (map-indexed vector (:authors entities))]
      (let [author (authors/find-or-create! tx (:name a) (:url a))]
        (crud/create! tx :authorships {:author-id     (:authors/id author)
                                       :readable-type "article"
                                       :readable-id   article-id
                                       :ordinal       i})))
    (jobs/enqueue! tx "tag-readable" {:readable-type "article" :readable-id article-id})
    (crud/by-id tx :articles article-id)))

(defn persist!
  "Finalize the placeholder article `article-id` from an extraction context +
   EntityResult in one transaction. Idempotent — re-running replaces the
   authorships instead of duplicating them. Returns the finalized article row."
  [ds article-id extract entities]
  (jdbc/with-transaction [tx ds]
    (finalize! tx article-id extract entities)))

(defn- ms [t0 t1] (long (/ (- t1 t0) 1000000)))

(defn extract-article!
  "Job handler: fetch `url`, extract body + entities, finalize the placeholder
   article `:article-id`, and record an extraction event. `:fetch-fn` and
   `:extract-entities` are injected — tests stub the network, and the entity
   step is the swappable LLM abstraction. Records a failure event and re-throws on any
   error so the worker marks the job for retry. Payload ids arrive as strings
   (jsonb doesn't preserve uuid), so `:article-id` is parsed."
  [ds {:keys [article-id url]} {:keys [fetch-fn extract-entities]}]
  (try
    (let [t0                       (System/nanoTime)
          {:keys [html final-url]} (fetch-fn url)
          t1                       (System/nanoTime)
          ex                       (extract/extract html (or final-url url))
          ent                      (let [e (schema/coerce-entities (extract-entities ex))]
                                     (when-not (schema/valid-entities? e)
                                       (throw (ex-info "entity extraction violated its contract"
                                                       {:error-class :invalid-entities :fatal? true
                                                        :explain     (schema/explain-entities e)})))
                                     e)
          t2                       (System/nanoTime)]
      (jdbc/with-transaction [tx ds]
        (finalize! tx (parse-uuid (str article-id)) ex ent)
        (events/record! tx {:url url :outcome :done :extract ex :entities ent
                            :durations {:fetch-ms (ms t0 t1) :extract-ms (ms t1 t2)}}))
      ent)
    (catch Throwable t
      ;; The failure event isn't tied to a rolled-back write, but its own insert
      ;; must never mask the real error — log and swallow a recording failure.
      (try
        (events/record! ds {:url url :outcome :failed
                            :error-class (or (:error-class (ex-data t)) :unknown)})
        (catch Throwable e
          (log/error e "failed to record extraction failure event" {:url url})))
      (throw t))))

;; ── inbound newsletter ingest (the :ingest-email job) ────────────────────

(defn ingest-email!
  "Job handler: fetch the raw .eml stored at `r2-key`, parse it, and record the
   newsletter issue on `user-id`'s queue in one transaction. Idempotent on the
   Message-ID — the payload value (threaded from the webhook) preferred, the
   parsed header as fallback. A missing object is fatal — retrying won't conjure
   it. Payload ids arrive as strings (jsonb drops uuid types), so `:user-id` is
   parsed."
  [ds store {:keys [user-id r2-key message-id]}]
  (let [raw (storage/get-object store r2-key)]
    (when-not raw
      (throw (ex-info "no stored object for r2-key"
                      {:r2-key r2-key :error-class :missing-object :fatal? true})))
    (let [parsed (email/parse raw)
          mid    (or (not-empty (str message-id)) (:message-id parsed))]
      (jdbc/with-transaction [tx ds]
        (newsletters/record-issue! tx (parse-uuid (str user-id))
                                   (assoc parsed :message-id mid :raw-key r2-key))))))

(defmethod ig/init-key :reader.ingest/ingest-email-handler [_ {store :storage}]
  (fn [ds payload] (ingest-email! ds store payload)))

(defmethod ig/init-key :reader.ingest/entity-extractor [_ _]
  ;; The default entity-extraction abstraction. Swap this key's value for an
  ;; LLM-backed extractor (same EntityResult contract) when the eval dashboard
  ;; shows the metadata path leaving authors on the table — no consumer changes.
  entities/from-metadata)

(defmethod ig/init-key :reader.ingest/extract-article-handler
  [_ {:keys [extract-entities]}]
  (fn [ds payload]
    (extract-article! ds payload {:fetch-fn fetch/fetch :extract-entities extract-entities})))

;; ── URL ingest entry point + status (the placeholder/poll flow) ──────────

(defn normalize-url
  "The canonical http(s) URL for a pasted string, or nil when it isn't a valid
   http(s) URL (reader.util.url/canonicalize). The canonical form is what we store
   and dedup on, so equivalent URLs don't fork into separate articles."
  [s]
  (url/canonicalize s))

(defn- job-for [ds article-id where-extra]
  (jdbc/execute! ds (sql/format
                     {:select   [:*] :from [:jobs]
                      ;; payload->>'article-id': HoneySQL's [:->> ...] renders as a
                      ;; function call, so use an infix raw fragment. The key is a
                      ;; constant (no injection surface); the id binds as a param.
                      :where    (into [:and
                                       [:= :queue-name "extract-article"]
                                       [:= [:raw "payload ->> 'article-id'"] (str article-id)]]
                                      where-extra)
                      :order-by [[:created-at :desc]] :limit 1})
                 crud/opts))

(defn- extracting? [ds article-id]
  (boolean (seq (job-for ds article-id [[:in :state ["pending" "in_progress"]]]))))

(defn start!
  "Begin URL ingest for `user-id`: upsert a placeholder article keyed on
   (canonical url, today's date), enqueue it on the user's queue, and enqueue an
   :extract-article job — all in one transaction. Returns {:queue-item :article}.
   Identity is (canonical_url, fetched_on): re-pasting the same url *today*
   reuses today's version, while pasting it on a later day creates a fresh
   version (its content may have changed). A fresh job is enqueued only when the
   version has no body yet and nothing is already extracting it. The upsert's
   unique-key lock serializes concurrent pastes of the same url+day, so the
   second transaction sees the first's job and doesn't double-enqueue."
  [ds user-id url]
  (jdbc/with-transaction [tx ds]
    (let [article (crud/upsert! tx :articles
                                {:title url :slug (slug/slugify url) :canonical-url url}
                                [:canonical-url :fetched-on]
                                {:updated-at [:now]})
          aid     (:articles/id article)
          qi      (reading/enqueue! tx user-id "article" aid {:source "manual" :url url})]
      (when (and (nil? (:articles/body-html article)) (not (extracting? tx aid)))
        (jobs/enqueue! tx "extract-article" {:article-id aid :url url}))
      {:queue-item qi :article article})))

(defn status
  "Ingest status of `article-id` for the poll UI: :done once its body is present
   (or its extract job finished), :failed if the job terminally failed, else
   :importing."
  [ds article-id]
  (let [st (:jobs/state (first (job-for ds article-id [])))]
    (cond
      (= "failed" st)                                                   :failed
      (some? (:articles/body-html (crud/by-id ds :articles article-id))) :done
      (= "done" st)                                                     :done
      :else                                                            :importing)))
