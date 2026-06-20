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
            [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [reader.domain.affiliations :as affiliations]
            [reader.domain.authors :as authors]
            [reader.db.crud :as crud]
            [reader.http :as http]
            [reader.ingest.events :as events]
            [reader.jobs :as jobs]
            [reader.papers.arxiv :as arxiv]
            [reader.papers.openalex :as openalex]
            [reader.domain.reading :as reading])
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

(def ^:private openalex-venue-types
  "OpenAlex source `type` -> our affiliation type enum. OpenAlex's documented set
   is journal / repository / conference / book series / ebook platform / metadata
   / other; anything unmapped (or a future new type) falls back to \"other\"."
  {"journal"        "journal"
   "repository"     "preprint"
   "conference"     "conference"
   "book series"    "journal"
   "ebook platform" "other"
   "metadata"       "other"
   "other"          "other"})

(defn- venue-type
  "Map an OpenAlex source type to our affiliation type enum (default \"other\")."
  [openalex-type]
  (get openalex-venue-types openalex-type "other"))

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

(defn- ref-url
  "The canonical external URL for a ref — what we link out to, and the `:url`
   key under which a failure is recorded (so the eval dashboard gets a real domain)."
  [{:keys [kind id]}]
  (case kind
    :arxiv (str "https://arxiv.org/abs/" id)
    :doi   (str "https://doi.org/" id)))

(def ^:private not-indexed-msg
  "paper not yet indexed by OpenAlex")

(defn- fetch-graph+body
  "Fetch the entity graph (+ arXiv body) for `ref` via the async `request-fn`.
   Returns {:graph :body :openalex-status}. OpenAlex is fired first so its request
   is in flight while the arXiv body is fetched — http-kit overlaps them, so we
   wait roughly one round trip, not two. For an arXiv paper OpenAlex misses (its
   back catalog), the arXiv Atom API is the names-only fallback."
  [request-fn {:keys [kind id] :as ref}]
  (let [arxiv?  (= :arxiv kind)
        work-p  (request-fn (openalex/work-url ref))     ; OpenAlex now in flight
        body    (when arxiv? (arxiv/fetch-body request-fn id))
        work    @work-p
        graph   (or (openalex/parse-graph (:body work))
                    (when arxiv? (arxiv/fetch-metadata request-fn id)))]
    {:graph graph :body body :openalex-status (:status work)}))

(defn extract-paper!
  "Job handler: fetch the OpenAlex graph for the placeholder paper `:paper-id` (+
   the reflowable arXiv HTML body, for arXiv), then upsert the entity graph and
   fill the paper in one transaction. `:request-fn` is injected (the network edge;
   tests stub it) and records a failure event before re-throwing, like
   reader.ingest/extract-article!.

   When no metadata is found we distinguish two failures: a DOI that OpenAlex
   answered 404 for is *not indexed yet* — terminal (re-running won't help for
   days) and surfaced honestly in the UI. Anything else (an OpenAlex outage, or a
   missing arXiv record) is left retryable. Payload ids arrive as strings (jsonb
   drops uuid + keyword types), so `:paper-id` is parsed and `:kind` re-keyworded."
  [ds {:keys [paper-id kind id]} {:keys [request-fn]}]
  (let [ref {:kind (keyword kind) :id id}]
    (try
      (let [{:keys [graph body openalex-status]} (fetch-graph+body request-fn ref)]
        (when-not graph
          (if (and (= :doi (:kind ref)) (= 404 openalex-status))
            (throw (ex-info not-indexed-msg
                            {:ref ref :fatal? true :error-class :paper-not-indexed}))
            (throw (ex-info "no metadata for paper (yet)"
                            {:ref ref :error-class :paper-not-found}))))
        (jdbc/with-transaction [tx ds]
          (finalize! tx (parse-uuid (str paper-id)) ref graph body)))
      (catch Throwable t
        ;; The failure event isn't tied to a rolled-back write, but its own insert
        ;; must never mask the real error — log and swallow a recording failure.
        (try
          (events/record! ds {:url (ref-url ref) :outcome :failed
                              :error-class (or (:error-class (ex-data t)) :unknown)})
          (catch Throwable e
            (log/error e "failed to record paper extraction failure event" {:ref ref})))
        (throw t)))))

(defmethod ig/init-key :reader.papers/extract-paper-handler [_ _]
  (fn [ds payload]
    (extract-paper! ds payload {:request-fn http/request!})))

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
   its extract job finished (a non-arXiv paper has metadata but no body),
   :not-indexed when the job terminally failed because OpenAlex hasn't indexed the
   paper yet (an honest, check-back-later state, not a hard error), :failed for any
   other terminal failure, else :importing."
  [ds paper-id]
  (let [job (first (job-for ds paper-id []))
        st  (:jobs/state job)]
    (cond
      (some? (:papers/body-html (crud/by-id ds :papers paper-id)))         :done
      (= "done" st)                                                        :done
      (and (= "failed" st) (= "paper-not-indexed" (:jobs/error-class job))) :not-indexed
      (= "failed" st)                                                      :failed
      :else                                                                :importing)))
