(ns reader.papers
  "Paper domain. `detect` classifies a pasted string as an arXiv or DOI
   reference; `start!` opens ingest (a placeholder paper + queue item + an
   :extract-paper job); `extract-paper!` is the job handler that fetches the
   OpenAlex graph + (for arXiv) the reflowable HTML body and upserts the canonical
   entity graph — venue, authors, institutions, author_affiliations, authorships —
   then fills the paper. The network edges (OpenAlex/arXiv fetch) are injected;
   the entity resolution goes through the identity-aware upsert
   (reader.db.crud/resolve-entity!), so the same author/institution under
   different name spellings collapses to one node."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [reader.affiliations :as affiliations]
            [reader.authors :as authors]
            [reader.db.crud :as crud]
            [reader.jobs :as jobs]
            [reader.papers.arxiv :as arxiv]
            [reader.papers.openalex :as openalex]
            [reader.reading :as reading])
  (:import (java.time Instant)))

(def ^:private arxiv-new #"(?i)(\d{4}\.\d{4,5})(?:v\d+)?")
(def ^:private arxiv-old #"(?i)([a-z][a-z\-]*(?:\.[a-z][a-z\-]*)?/\d{7})(?:v\d+)?")
(def ^:private arxiv-whole #"(?i)(?:\d{4}\.\d{4,5}|[a-z][a-z\-]*(?:\.[a-z][a-z\-]*)?/\d{7})(?:v\d+)?")
(def ^:private doi-re #"(?i)(10\.\d{4,9}/[^\s\"<>]+)")

(defn- arxiv-id
  "The bare arXiv id (version stripped, case preserved) found in `s`, or nil."
  [s]
  (or (some-> (re-find arxiv-new s) second)
      (some-> (re-find arxiv-old s) second)))

(defn- arxiv-ref?
  "True when `s` is an arXiv reference: an arxiv.org/ar5iv URL, an arXiv: prefix,
   or a bare arXiv id."
  [s]
  (boolean (or (re-find #"(?i)\b(?:ar5iv|arxiv\.org)\b" s)
               (re-find #"(?i)^arxiv:" s)
               (re-matches arxiv-whole s))))

(defn detect
  "A pasted string -> {:kind :arxiv :id <id>} (case preserved) |
   {:kind :doi :id <lowercased doi>} | nil."
  [s]
  (when-let [s (some-> s str/trim not-empty)]
    (cond
      (and (arxiv-ref? s) (arxiv-id s)) {:kind :arxiv :id (arxiv-id s)}
      (re-find doi-re s)                {:kind :doi :id (str/lower-case (second (re-find doi-re s)))}
      :else                             nil)))

;; ── ingest: placeholder + the :extract-paper job ─────────────────────────

(defn- venue-type
  "Map an OpenAlex source type to our affiliation type enum."
  [openalex-type]
  (case openalex-type
    "repository" "preprint"
    "journal"    "journal"
    "other"))

(defn- clear-authorships! [tx paper-id]
  (jdbc/execute! tx (sql/format {:delete-from :authorships
                                 :where       [:and
                                               [:= :readable-type "paper"]
                                               [:= :readable-id paper-id]]})))

(defn- link-author-affiliation!
  "Idempotently link an author to an institution (author_affiliations). Find-then-
   create rather than upsert: the unique key is NULLS NOT DISTINCT over
   (author, affiliation, starts_on), and we only ever write the open-ended
   (starts_on NULL) link, so a re-run finds the existing row."
  [tx author-id affiliation-id]
  (when-not (crud/find-1 tx :author-affiliations {:author-id author-id :affiliation-id affiliation-id})
    (crud/create! tx :author-affiliations
                  {:author-id author-id :affiliation-id affiliation-id :role "author"})))

(defn- finalize!
  "Write body of the job, scoped to `tx`: resolve the venue, fill the paper, then
   (re)build the author graph in byline order — each author resolved to a canonical
   identity, their institutions resolved and linked. Idempotent: re-running
   replaces authorships and skips already-present institution links. Returns the
   paper row."
  [tx paper-id ref graph body]
  (let [venue (when-let [v (:venue graph)]
                (affiliations/resolve! tx {:name (:name v) :openalex-id (:openalex-id v)
                                           :type (venue-type (:type v))}))]
    (crud/update! tx :papers paper-id
                  (cond-> {:updated-at (Instant/now)}
                    (:title graph)         (assoc :title (:title graph))
                    (:abstract graph)      (assoc :abstract (:abstract graph))
                    (:published graph)     (assoc :published-at (:published graph))
                    body                   (assoc :body-html body)
                    venue                  (assoc :affiliation-id (:affiliations/id venue))
                    (= :arxiv (:kind ref)) (assoc :arxiv-id (:id ref))
                    (= :doi (:kind ref))   (assoc :doi (:id ref))))
    (clear-authorships! tx paper-id)
    (doseq [[i a] (map-indexed vector (:authors graph))
            :when (:name a)]
      (let [aid (:authors/id (authors/resolve! tx (select-keys a [:name :orcid :openalex-id])))]
        (crud/create! tx :authorships {:author-id aid :readable-type "paper"
                                       :readable-id paper-id :ordinal i})
        (doseq [inst (:institutions a) :when (:name inst)]
          (let [fid (:affiliations/id (affiliations/resolve! tx (assoc (select-keys inst [:name :ror :openalex-id])
                                                                       :type "institution")))]
            (link-author-affiliation! tx aid fid)))))
    (crud/by-id tx :papers paper-id)))

(defn extract-paper!
  "Job handler: fetch the OpenAlex graph for the placeholder paper `:paper-id`
   (+ the reflowable arXiv HTML body, for arXiv), then upsert the entity graph and
   fill the paper in one transaction. `openalex-fetch`/`body-fetch` are injected
   (the network edges; tests stub them). A missing OpenAlex record throws
   non-fatally so the worker retries — OpenAlex may not have indexed a brand-new
   paper yet. Payload ids arrive as strings (jsonb drops uuid + keyword types), so
   `:paper-id` is parsed and `:kind` re-keyworded."
  [ds {:keys [paper-id kind id]} {:keys [openalex-fetch body-fetch meta-fetch]}]
  (let [ref   {:kind (keyword kind) :id id}
        ;; OpenAlex first (the rich graph); for an arXiv paper it doesn't have
        ;; (the back catalog), fall back to the arXiv API for names-only metadata.
        graph (or (openalex/fetch openalex-fetch ref)
                  (when (and meta-fetch (= :arxiv (:kind ref)))
                    (arxiv/fetch-metadata meta-fetch id)))
        body  (when (= :arxiv (:kind ref)) (arxiv/fetch-body body-fetch id))]
    (when-not graph
      (throw (ex-info "no metadata for paper (yet)"
                      {:ref ref :error-class :paper-not-found})))
    (jdbc/with-transaction [tx ds]
      (finalize! tx (parse-uuid (str paper-id)) ref graph body))))

(defmethod ig/init-key :reader.papers/extract-paper-handler [_ _]
  (fn [ds payload]
    (extract-paper! ds payload {:openalex-fetch openalex/http-get
                                :body-fetch     openalex/http-get
                                :meta-fetch     openalex/http-get})))

;; ── entry point + status (placeholder/poll flow) ─────────────────────────

(defn- find-paper [ds {:keys [kind id]}]
  (case kind
    :arxiv (crud/find-1 ds :papers {:arxiv-id id})
    :doi   (crud/find-1 ds :papers {:doi id})))

(defn- placeholder-attrs [{:keys [kind id]}]
  (cond-> {:title (case kind :arxiv (str "arXiv:" id) :doi (str "doi:" id))}
    (= kind :arxiv) (assoc :arxiv-id id)
    (= kind :doi)   (assoc :doi id)))

(defn- job-for [ds paper-id where-extra]
  (jdbc/execute! ds (sql/format
                     {:select   [:*] :from [:jobs]
                      ;; payload ->> 'paper-id': the key is a constant (no
                      ;; injection surface); the id binds as a param.
                      :where    (into [:and
                                       [:= :queue-name "extract-paper"]
                                       [:= [:raw "payload ->> 'paper-id'"] (str paper-id)]]
                                      where-extra)
                      :order-by [[:created-at :desc]] :limit 1})
                 crud/opts))

(defn- needs-extract?
  "True when no :extract-paper job for this paper is pending, running, or done —
   so a fresh paper (or one whose only prior job failed) gets (re)enqueued, but a
   succeeded or in-flight one is left alone."
  [ds paper-id]
  (empty? (job-for ds paper-id [[:in :state ["pending" "in_progress" "done"]]])))

(defn start!
  "Begin paper ingest for `user-id` from a detected `ref` ({:kind :arxiv/:doi
   :id}): find-or-create the placeholder paper (identity is its arXiv id / DOI),
   add it to the user's queue, and enqueue an :extract-paper job unless one has
   already succeeded or is in flight — all in one transaction. Returns
   {:queue-item :paper}."
  [ds user-id ref]
  (jdbc/with-transaction [tx ds]
    (let [paper (or (find-paper tx ref) (crud/create! tx :papers (placeholder-attrs ref)))
          pid   (:papers/id paper)
          qi    (reading/enqueue! tx user-id "paper" pid
                                  {:source "manual" :ref (str (name (:kind ref)) ":" (:id ref))})]
      (when (needs-extract? tx pid)
        (jobs/enqueue! tx "extract-paper" {:paper-id pid :kind (name (:kind ref)) :id (:id ref)}))
      {:queue-item qi :paper paper})))

(defn status
  "Ingest status of `paper-id` for the poll UI: :done once its body is present or
   its extract job finished (a non-arXiv paper has metadata but no body), :failed
   if the job terminally failed, else :importing."
  [ds paper-id]
  (let [st (:jobs/state (first (job-for ds paper-id [])))]
    (cond
      (= "failed" st)                                                :failed
      (some? (:papers/body-html (crud/by-id ds :papers paper-id)))   :done
      (= "done" st)                                                  :done
      :else                                                          :importing)))
