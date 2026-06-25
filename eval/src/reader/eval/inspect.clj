(ns reader.eval.inspect
  "Read-only drill-down over the reader's extraction_events / tagging_events and
   the corpus they describe, for the evals app (ADR 0006). Constant SQL over the
   product's `public` tables — no writes, no user input. The dashboard renders
   per-case views from these; the aggregates live alongside in reader.eval.metrics.

   Returns plain unqualified-kebab maps (jsonb provenance round-trips to Clojure
   data via reader.db.types), so a page can render a case without reshaping."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn- q  [ds sqlmap] (jdbc/execute!     ds (sql/format sqlmap) opts))
(defn- q1 [ds sqlmap] (jdbc/execute-one! ds (sql/format sqlmap) opts))

(def ^:private readable-table
  "readable_type string -> the corpus table holding that readable."
  {"article" :articles "paper" :papers "newsletter_issue" :newsletter-issues})

(defn readable-summary
  "Title + the content fields the tagger/extractor saw, for any readable type.
   nil when the type is unknown or the row is gone (a readable deleted after its
   event was recorded)."
  [ds rtype rid]
  (when-let [table (readable-table rtype)]
    (when-let [row (q1 ds {:select [:*] :from [table] :where [:= :id rid]})]
      (-> (select-keys row [:title :abstract :word-count])
          (assoc :type rtype :id rid)))))

(defn- titles-for
  "Map of [readable-type readable-id] -> title, one query per readable table."
  [ds refs]
  (into {}
        (for [[rtype group] (group-by first refs)
              :let  [table (readable-table rtype)
                     rows  (when table
                             (q ds {:select [:id :title] :from [table]
                                    :where  [:in :id (distinct (map second group))]}))]
              row   rows]
          [[rtype (:id row)] (:title row)])))

;; ── tagging ──────────────────────────────────────────────────────────────

(defn- latest-tagging-event [ds rtype rid]
  (q1 ds {:select   [:*] :from [:tagging-events]
          :where    [:and [:= :readable-type rtype] [:= :readable-id rid]]
          :order-by [[:created-at :desc]] :limit 1}))

(defn- assigned-tags
  "The baseline tags stored for a readable — label + confidence, by label."
  [ds rtype rid]
  (q ds {:select   [:t.label :rt.confidence]
         :from     [[:readable-tags :rt]]
         :join     [[:tags :t] [:= :t.id :rt.tag-id]]
         :where    [:and [:= :rt.readable-type rtype] [:= :rt.readable-id rid]]
         :order-by [[:t.label :asc]]}))

(defn tagging-case
  "Everything to inspect one readable's tagging: the latest attempt's event, the
   model's proposed labels + vocabulary size (from provenance), the tags actually
   assigned to the shared baseline, and the content the model saw."
  [ds rtype rid]
  (let [ev   (latest-tagging-event ds rtype rid)
        prov (:provenance ev)]
    {:event      (select-keys ev [:outcome :error-class :model :tag-count :duration-ms :created-at])
     :proposed   (:labels prov)
     :vocab-size (:vocab-size prov)
     :assigned   (assigned-tags ds rtype rid)
     :readable   (readable-summary ds rtype rid)}))

(defn tagging-cases
  "Recent tagging attempts as list rows: the event's first-class columns plus the
   readable's title. Newest first."
  [ds {:keys [limit] :or {limit 50}}]
  (let [events (q ds {:select   [:readable-type :readable-id :outcome :model
                                 :tag-count :duration-ms :error-class :created-at]
                      :from     [:tagging-events]
                      :order-by [[:created-at :desc]]
                      :limit    limit})
        titles (titles-for ds (map (juxt :readable-type :readable-id) events))]
    (mapv #(assoc % :title (titles [(:readable-type %) (:readable-id %)])) events)))

;; ── extraction ─────────────────────────────────────────────────────────────
;; extraction_events are keyed by url (the article isn't finalized when the event
;; is written), so a case joins back to the corpus on articles.canonical_url, the
;; same hook reader.admin/recovery uses.

(defn- latest-extraction-event [ds url]
  (q1 ds {:select   [:*] :from [:extraction-events]
          :where    [:= :url url]
          :order-by [[:created-at :desc]] :limit 1}))

(defn- article-by-url [ds url]
  (q1 ds {:select [:id :title :word-count :affiliation-id]
          :from   [:articles] :where [:= :canonical-url url]}))

(defn- byline
  "The article's authors in byline order — what extraction resolved into the graph."
  [ds article-id]
  (q ds {:select   [:a.name :a.slug :au.ordinal]
         :from     [[:authorships :au]]
         :join     [[:authors :a] [:= :a.id :au.author-id]]
         :where    [:and [:= :au.readable-type "article"] [:= :au.readable-id article-id]]
         :order-by [[:au.ordinal :asc]]}))

(defn- source-of [ds affiliation-id]
  (when affiliation-id
    (q1 ds {:select [:name :slug :type] :from [:affiliations] :where [:= :id affiliation-id]})))

(defn extraction-case
  "Everything to inspect one article's entity extraction: the latest attempt's
   event, the full per-field provenance bag, the entities actually resolved into
   the graph (byline in order, the source it was published in), and the content,
   for the article that landed at `url`."
  [ds url]
  (let [ev  (latest-extraction-event ds url)
        art (article-by-url ds url)
        aid (:id art)]
    {:event       (select-keys ev [:outcome :error-class :extractor :domain :word-count
                                   :body-confidence :entity-confidence :author-count
                                   :title-source :author-source :affiliation-source
                                   :published-source :fetch-ms :extract-ms :created-at])
     :provenance  (:provenance ev)
     :article     (when art (select-keys art [:id :title :word-count]))
     :authors     (when aid (byline ds aid))
     :affiliation (source-of ds (:affiliation-id art))}))

(defn extraction-cases
  "Recent extraction attempts as list rows (newest first): the event's first-class
   coverage/confidence signals keyed by url."
  [ds {:keys [limit] :or {limit 50}}]
  (q ds {:select   [:url :domain :outcome :error-class :body-confidence
                    :entity-confidence :author-count :title-source :author-source
                    :affiliation-source :word-count :created-at]
         :from     [:extraction-events]
         :order-by [[:created-at :desc]]
         :limit    limit}))
