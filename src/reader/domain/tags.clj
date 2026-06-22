(ns reader.domain.tags
  "Tags: the shared vocabulary, the per-readable baseline assigned by the
   infer-tags abstraction, and per-user overrides on a queue item.

   Tags are intrinsic to content, so the baseline lives on the readable (shared
   across users); a user's additions/removals are a sparse delta on their queue
   item. Effective tags for a queue item = (baseline minus suppressions) plus
   additions.

   A newly proposed tag folds into an existing one when their label embeddings
   are similar enough (cosine >= threshold) — the guard against vocabulary
   explosion. Similarity is brute-forced here in Clojure; the corpus is small.
   Joins are done in Clojure over plain CRUD reads, as elsewhere in the domain."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [reader.db.crud :as crud]
            [reader.util.slug :as slug]))

(def default-threshold
  "Cosine similarity at or above which a proposed tag is treated as a duplicate
   of an existing one rather than a new tag. 0.90 recurs across embedding-dedup
   practice; tune as the vocabulary grows."
  0.90)

(def ^:private type->str
  "The normalized readable :type keyword -> the string stored in readable_type."
  {:article "article" :paper "paper" :newsletter-issue "newsletter_issue"})

;; ── pure: similarity ─────────────────────────────────────────────────────

(defn- ->doubles [v] (when (seq v) (mapv double v)))

(defn cosine
  "Cosine similarity of two numeric vectors, in [-1.0, 1.0]. 0.0 when either is
   empty, has zero magnitude, or the two differ in length — a length mismatch
   means different embedding spaces (a model/dimensions change), which aren't
   comparable, so treat them as unrelated rather than truncating to a spurious
   score that could wrongly merge or split the vocabulary."
  [a b]
  (if (or (empty? a) (empty? b) (not= (count a) (count b)))
    0.0
    (let [dot (reduce + (map * a b))
          ma  (Math/sqrt (reduce + (map #(* % %) a)))
          mb  (Math/sqrt (reduce + (map #(* % %) b)))]
      (if (or (zero? ma) (zero? mb)) 0.0 (/ dot (* ma mb))))))

(defn nearest
  "Pure. The `vocab` entry most similar to `embedding`, as {:tag entry :score s},
   or nil when there is nothing comparable. Entries are {:id :slug :label
   :embedding}; those without an embedding are skipped."
  [vocab embedding]
  (when (seq embedding)
    (->> vocab
         (keep (fn [entry]
                 (when-let [e (:embedding entry)]
                   {:tag entry :score (cosine embedding e)})))
         (sort-by :score >)
         first)))

;; ── vocabulary + tag resolution ──────────────────────────────────────────

(defn- ->entry
  "A tags row -> the plain-keyed vocabulary entry the pure helpers use."
  [row]
  {:id        (:tags/id row)
   :slug      (:tags/slug row)
   :label     (:tags/label row)
   :embedding (->doubles (:tags/embedding row))})

(defn vocabulary
  "Every tag as a plain-keyed entry {:id :slug :label :embedding} — the dedup set
   that infer-tags reconciles new proposals against."
  [ds]
  (mapv ->entry (crud/find-many ds :tags)))

(defn- create-tag!
  "Insert a tag, or return the existing one on a slug collision (a concurrent
   worker minted it first). Returns a vocabulary entry."
  [tx {:keys [slug label embedding]}]
  (->entry (crud/upsert! tx :tags
                         {:slug slug :label label :embedding embedding}
                         [:slug]
                         {:label label})))

(defn resolve-tag!
  "Resolve a proposed {:label :embedding} against `vocab` (preloaded entries):
   an exact slug match, else a near-duplicate (cosine >= `threshold`), else a
   freshly created tag. Returns the resolved vocabulary entry. The only write is
   the create, so the caller can fold the result back into `vocab` to dedup a
   batch of proposals against itself as well."
  [tx vocab threshold {:keys [label embedding]}]
  (let [s (slug/slugify label)]
    (or (first (filter #(= s (:slug %)) vocab))
        (let [{:keys [tag score]} (nearest vocab embedding)]
          (when (and tag (>= score threshold)) tag))
        (create-tag! tx {:slug s :label label :embedding embedding}))))

(defn resolve-tags!
  "Resolve a batch of {:label :embedding} proposals to tag entries, deduping
   against the existing vocabulary and against each other. Returns the resolved
   entries, one per proposal, in order."
  [tx threshold proposals]
  (first
   (reduce (fn [[acc vocab] proposal]
             (let [entry (resolve-tag! tx vocab threshold proposal)]
               [(conj acc entry)
                (if (some #(= (:id entry) (:id %)) vocab) vocab (conj vocab entry))]))
           [[] (vocabulary tx)]
           proposals)))

;; ── baseline (shared, per-readable) ──────────────────────────────────────

(defn set-baseline!
  "Replace the baseline tags for (readable-type, readable-id) with `assignments`
   ({:tag-id :confidence}) in the current transaction. Idempotent: re-running
   yields the same final set, never duplicates."
  [tx readable-type readable-id assignments]
  (jdbc/execute! tx (sql/format {:delete-from :readable-tags
                                 :where       [:and
                                               [:= :readable-type readable-type]
                                               [:= :readable-id readable-id]]}))
  (doseq [{:keys [tag-id confidence]} assignments]
    (crud/create! tx :readable-tags
                  {:tag-id        tag-id
                   :readable-type readable-type
                   :readable-id   readable-id
                   :confidence    (or confidence 1.0)})))

(defn set-readable-embedding!
  "Upsert the embedding for a readable (one per readable). Re-running replaces it."
  [tx readable-type readable-id embedding model]
  (crud/upsert! tx :readable-embeddings
                {:readable-type readable-type :readable-id readable-id
                 :embedding     embedding     :model       model}
                [:readable-type :readable-id]
                {:embedding :excluded.embedding :model :excluded.model}))

;; ── effective tags (baseline + per-user override) ────────────────────────

(defn- tags-by-id [ds ids]
  (into {} (map (juxt :tags/id ->entry)) (crud/find-in ds :tags :id (distinct ids))))

(defn baseline-by-readable
  "Baseline tag entries grouped by [readable-type readable-id], for the given
   `refs` ([type-str id] pairs). Two reads joined in Clojure."
  [ds refs]
  (let [ids   (distinct (map second refs))
        links (crud/find-in ds :readable-tags :readable-id ids)
        by-id (tags-by-id ds (map :readable-tags/tag-id links))]
    (-> (group-by (juxt :readable-tags/readable-type :readable-tags/readable-id) links)
        (update-vals (fn [ls] (vec (keep (comp by-id :readable-tags/tag-id) ls)))))))

(defn overrides-by-queue-item
  "User override entries grouped by queue-item-id, each carrying its :op."
  [ds queue-item-ids]
  (let [rows  (crud/find-in ds :queue-item-tags :queue-item-id (distinct queue-item-ids))
        by-id (tags-by-id ds (map :queue-item-tags/tag-id rows))]
    (-> (group-by :queue-item-tags/queue-item-id rows)
        (update-vals (fn [rs]
                       (vec (keep (fn [r]
                                    (some-> (by-id (:queue-item-tags/tag-id r))
                                            (assoc :op (:queue-item-tags/op r))))
                                  rs)))))))

(defn resolve-effective
  "Pure. The effective tag set for one item: baseline tags not suppressed, plus
   user-added tags, deduped by id and sorted by label. `baseline` is a seq of
   entries; `overrides` is a seq of entries carrying :op (\"add\"|\"suppress\")."
  [baseline overrides]
  (let [suppressed (into #{} (comp (filter #(= "suppress" (:op %))) (map :id)) overrides)
        kept       (remove #(suppressed (:id %)) baseline)
        kept-ids   (into #{} (map :id) kept)
        added      (->> overrides
                        (filter #(= "add" (:op %)))
                        (remove #(kept-ids (:id %))))]
    (->> (concat kept added)
         (map #(select-keys % [:id :slug :label]))
         distinct
         (sort-by (comp str :label))
         vec)))

(defn attach-effective
  "Attach :tags (the effective tag set) to each queue `item` (carrying :type,
   :id, :queue-item-id). Batched: one read of baseline tags by readable and one
   of overrides by queue item, resolved per item in Clojure."
  [ds items]
  (let [refs (map (fn [it] [(type->str (:type it)) (:id it)]) items)
        base (baseline-by-readable ds refs)
        ov   (overrides-by-queue-item ds (map :queue-item-id items))]
    (mapv (fn [it]
            (assoc it :tags (resolve-effective
                             (get base [(type->str (:type it)) (:id it)] [])
                             (get ov (:queue-item-id it) []))))
          items)))

;; ── filtering (pure, for the reading list) ───────────────────────────────

(defn distinct-tags
  "The distinct tags across `items` (each carrying :tags), sorted by label — the
   set the reading-list filter bar offers."
  [items]
  (->> (mapcat :tags items)
       distinct
       (sort-by (comp str :label))
       vec))

(defn with-tag
  "The `items` whose effective tags include `slug`. `slug` nil returns them all
   (the unfiltered view)."
  [items slug]
  (if slug
    (filterv #(some (comp #{slug} :slug) (:tags %)) items)
    (vec items)))

;; ── per-user overrides (write) ───────────────────────────────────────────

(defn set-override!
  "Pin (op \"add\") or hide (op \"suppress\") `tag-id` on `queue-item-id`. Upserts,
   so toggling flips the op rather than duplicating."
  [ds queue-item-id tag-id op]
  (crud/upsert! ds :queue-item-tags
                {:queue-item-id queue-item-id :tag-id tag-id :op op}
                [:queue-item-id :tag-id]
                {:op op}))

(defn clear-override!
  "Drop any override for `tag-id` on `queue-item-id`, reverting it to baseline."
  [ds queue-item-id tag-id]
  (jdbc/execute! ds (sql/format {:delete-from :queue-item-tags
                                 :where       [:and
                                               [:= :queue-item-id queue-item-id]
                                               [:= :tag-id tag-id]]})))

(defn- baseline-has? [ds readable-type readable-id tag-id]
  (some? (crud/find-1 ds :readable-tags {:readable-type readable-type
                                         :readable-id   readable-id
                                         :tag-id        tag-id})))

(defn find-or-create-label!
  "Resolve a user-entered tag by its slug, creating it (no embedding) when new.
   For user overrides the user chose the label, so there's no embedding dedup.
   The label is capped to 60 chars (matching the inferred-tag cap) so a pasted
   essay can't become a tag."
  [ds label]
  (let [l (-> label str str/trim str/lower-case)
        l (subs l 0 (min (count l) 60))]
    (->entry (crud/upsert! ds :tags {:slug (slug/slugify l) :label l} [:slug] {:label l}))))

(defn add-tag!
  "Make `tag-id` effective on `queue-item-id`: clear a suppression when the tag is
   already in the readable's baseline, else record an explicit add."
  [ds queue-item-id readable-type readable-id tag-id]
  (if (baseline-has? ds readable-type readable-id tag-id)
    (clear-override! ds queue-item-id tag-id)
    (set-override! ds queue-item-id tag-id "add")))

(defn remove-tag!
  "Make `tag-id` not effective on `queue-item-id`: suppress it when it's a baseline
   tag, else drop the user's prior add."
  [ds queue-item-id readable-type readable-id tag-id]
  (if (baseline-has? ds readable-type readable-id tag-id)
    (set-override! ds queue-item-id tag-id "suppress")
    (clear-override! ds queue-item-id tag-id)))

(defn effective-for-queue-item
  "The effective tag set for a single raw queue_items row (its baseline minus
   suppressions plus additions)."
  [ds {:queue-items/keys [id readable-type readable-id]}]
  (resolve-effective (get (baseline-by-readable ds [[readable-type readable-id]])
                          [readable-type readable-id] [])
                     (get (overrides-by-queue-item ds [id]) id [])))
