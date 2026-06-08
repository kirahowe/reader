(ns reader.readables
  "Assembles the cross-table readable catalog: every readable (article, paper,
   newsletter issue) normalized to one shape, joined to its source affiliation
   and its authors. This is the shared library; the per-user reading queue
   (reader.reading) is drawn from it. The join happens in Clojure over a
   handful of plain CRUD reads — the data is small and the result is far
   easier to test as a value than as SQL. Note this is the readable's *own*
   affiliation (its publisher/source); an author's affiliations are a
   separate relation, surfaced on the author page, not here."
  (:require [clojure.string :as str]
            [reader.db.crud :as crud]))

(def ^:private readable-types
  "Per readable table: the app-facing type keyword, the string stored in
   `authorships.readable_type`, and the column holding the display title."
  {:articles          {:type :article          :type-str "article"          :title-key :articles/title}
   :papers            {:type :paper            :type-str "paper"            :title-key :papers/title}
   :newsletter-issues {:type :newsletter-issue :type-str "newsletter_issue" :title-key :newsletter-issues/subject}})

(def ^:private type->table
  "The normalized item's `:type` keyword -> the table that backs it."
  (into {} (map (fn [[table {:keys [type]}]] [type table])) readable-types))

(defn- index-by [k rows]
  (into {} (map (juxt k identity)) rows))

(defn- author-ref [{:authors/keys [name slug]}]
  {:name name :slug slug})

(defn- source-ref [{:affiliations/keys [name slug]}]
  {:name name :slug slug})

(defn- normalize
  "One readable row -> the common item shape, joined to source + authors."
  [table row {:keys [affs-by-id authors-by-id authorships-by-readable]}]
  (let [{:keys [type type-str title-key]} (readable-types table)
        id     (get row (keyword (name table) "id"))
        aff-id (get row (keyword (name table) "affiliation-id"))]
    {:type    type
     :table   table
     :id      id
     :title   (get row title-key)
     :source  (some-> (get affs-by-id aff-id) source-ref)
     :authors (->> (get authorships-by-readable [type-str id])
                   (sort-by :authorships/ordinal)
                   (map :authorships/author-id)
                   (keep authors-by-id)
                   (mapv author-ref))}))

(defn assemble
  "Pure. Takes the raw rows (kebab-qualified, as `reader.db.crud` returns
   them) for the three readable tables plus affiliations, authorships, and
   authors; returns the normalized catalog, ordered case-insensitively by
   title."
  [{:keys [articles papers newsletter-issues affiliations authorships authors]}]
  (let [ctx   {:affs-by-id              (index-by :affiliations/id affiliations)
               :authors-by-id           (index-by :authors/id authors)
               :authorships-by-readable (group-by (juxt :authorships/readable-type
                                                        :authorships/readable-id)
                                                  authorships)}
        items (concat (map #(normalize :articles % ctx) articles)
                      (map #(normalize :papers % ctx) papers)
                      (map #(normalize :newsletter-issues % ctx) newsletter-issues))]
    (sort-by (comp str/lower-case :title) items)))

(defn- affiliation-ids [table rows]
  (keep #(get % (keyword (name table) "affiliation-id")) rows))

(defn- catalog-from-rows
  "Given already-fetched readable rows per table (:articles/:papers/:newsletter-issues),
   fetch their affiliations, authorships, and authors and assemble the normalized catalog."
  [ds {:keys [articles papers newsletter-issues] :as readables}]
  (let [aff-ids      (distinct (concat (affiliation-ids :articles articles)
                                       (affiliation-ids :papers papers)
                                       (affiliation-ids :newsletter-issues newsletter-issues)))
        readable-ids (distinct (concat (map :articles/id articles)
                                       (map :papers/id papers)
                                       (map :newsletter-issues/id newsletter-issues)))
        authorships  (crud/find-in ds :authorships :readable-id readable-ids)
        author-ids   (distinct (map :authorships/author-id authorships))]
    (assemble (assoc readables
                     :affiliations (crud/find-in ds :affiliations :id aff-ids)
                     :authorships  authorships
                     :authors      (crud/find-in ds :authors :id author-ids)))))

(defn catalog-of
  "Every readable named by `refs`, normalized and joined to source + authors.
   `refs` is a seq of `[type id]` pairs (the `:type`/`:id` a normalized item
   carries). Reads only those readable rows plus the affiliations, authorships,
   and authors they reference — so the per-user queue (reader.reading) that
   draws on this touches only what the user has queued, not the whole library."
  [ds refs]
  (let [ids-by-table (-> (group-by (comp type->table first) refs)
                         (update-vals #(distinct (map second %))))]
    (catalog-from-rows ds
                       {:articles          (crud/find-in ds :articles          :id (:articles ids-by-table))
                        :papers            (crud/find-in ds :papers            :id (:papers ids-by-table))
                        :newsletter-issues (crud/find-in ds :newsletter-issues :id (:newsletter-issues ids-by-table))})))

(defn find-one
  "The full readable named by `[type id]`: its normalized item (title, source,
   authors — the list shape) under :item, plus the raw row (body, abstract,
   links, …) under :row. nil for an unknown type or a missing row. `catalog-of`
   deliberately omits bodies, so the reader view needs this single full fetch."
  [ds type id]
  (when-let [table (type->table type)]
    (when-let [row (crud/by-id ds table id)]
      {:item (first (catalog-from-rows ds {table [row]}))
       :row  row})))
