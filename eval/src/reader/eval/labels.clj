(ns reader.eval.labels
  "Golden labels — the feedback the Workbench collects (ADR 0006), and scoring
   production output against them. A label stores the *materialized* golden truth
   (the correct tag-slug set, or the correct byline + source), so scoring re-reads
   current production any time and compares — production can change (re-tagging,
   re-extraction) without staling the label. Writes only eval_labels; reads the
   reader's public tables. Joins in Clojure / plain SQL, as in the reader domain."
  (:require [clojure.set :as set]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [reader.db.crud :as crud]
            [reader.eval.scoring :as scoring]))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})
(defn- q  [ds m] (jdbc/execute!     ds (sql/format m) opts))
(defn- q1 [ds m] (jdbc/execute-one! ds (sql/format m) opts))

(def ^:private conflict-keys [:feature :readable-type :readable-id :subject-url])
(def ^:private on-conflict   {:label :excluded.label :labeled-by :excluded.labeled-by :updated-at [:now]})

;; ── tagging ──────────────────────────────────────────────────────────────────

(defn apply-corrections
  "Pure: the corrected slug set — production `assigned` slugs minus the operator-
   flagged `wrong` ones, plus the operator-`added` ones. The golden truth for a
   case; used for both tag sets and bylines (author slugs). The confirmed case
   (nothing flagged or added) just returns `assigned`."
  [assigned wrong added]
  (into (set/difference (set assigned) (set wrong)) added))

(defn production-tag-slugs
  "The tag slugs currently assigned to a readable's shared baseline."
  [ds rtype rid]
  (into #{} (map :slug)
        (q ds {:select [:t.slug]
               :from   [[:readable-tags :rt]]
               :join   [[:tags :t] [:= :t.id :rt.tag-id]]
               :where  [:and [:= :rt.readable-type rtype] [:= :rt.readable-id rid]]})))

(defn record-tagging!
  "Upsert the golden tag-slug set for a readable (one label per readable)."
  [ds {:keys [readable-type readable-id golden labeled-by]}]
  (crud/upsert! ds :eval-labels
                {:feature "tagging" :readable-type readable-type :readable-id readable-id
                 :label {:tags (vec golden)} :labeled-by labeled-by}
                conflict-keys on-conflict))

(defn- tagging-label-rows [ds]
  (q ds {:select [:readable-type :readable-id :label] :from [:eval-labels] :where [:= :feature "tagging"]}))

(defn tagging-score
  "Micro-averaged precision/recall/F1 of the current baseline tags against the
   golden tag sets, plus how many readables are labeled."
  [ds]
  (let [rows  (tagging-label-rows ds)
        cases (for [{:keys [readable-type readable-id label]} rows]
                {:golden    (set (:tags label))
                 :predicted (production-tag-slugs ds readable-type readable-id)})]
    (assoc (scoring/prf cases) :labeled (count rows))))

;; ── extraction ────────────────────────────────────────────────────────────────

(defn production-byline-slugs
  "Author slugs currently resolved into the graph for the article at `url`."
  [ds url]
  (if-let [art (q1 ds {:select [:id] :from [:articles] :where [:= :canonical-url url]})]
    (into #{} (map :slug)
          (q ds {:select [:a.slug]
                 :from   [[:authorships :au]]
                 :join   [[:authors :a] [:= :a.id :au.author-id]]
                 :where  [:and [:= :au.readable-type "article"] [:= :au.readable-id (:id art)]]}))
    #{}))

(defn production-source
  "The source (affiliation) slug currently on the article at `url`, or nil."
  [ds url]
  (:slug (q1 ds {:select [:aff.slug]
                 :from   [[:articles :ar]]
                 :join   [[:affiliations :aff] [:= :aff.id :ar.affiliation-id]]
                 :where  [:= :ar.canonical-url url]})))

(defn record-extraction!
  "Upsert the golden byline (author slugs) + source slug for the article at
   `subject-url` (one label per url)."
  [ds {:keys [subject-url authors source labeled-by]}]
  (crud/upsert! ds :eval-labels
                {:feature "extraction" :subject-url subject-url
                 :label {:authors (vec authors) :source source} :labeled-by labeled-by}
                conflict-keys on-conflict))

(defn- extraction-label-rows [ds]
  (q ds {:select [:subject-url :label] :from [:eval-labels] :where [:= :feature "extraction"]}))

(defn extraction-score
  "Byline precision/recall/F1 (author sets) and source accuracy of current
   production against the golden labels."
  [ds]
  (let [rows (extraction-label-rows ds)]
    {:byline (assoc (scoring/prf
                     (for [{:keys [subject-url label]} rows]
                       {:golden    (set (:authors label))
                        :predicted (production-byline-slugs ds subject-url)}))
                    :labeled (count rows))
     :source (scoring/accuracy
              (for [{:keys [subject-url label]} rows]
                {:golden (:source label) :predicted (production-source ds subject-url)}))}))
