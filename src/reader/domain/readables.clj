(ns reader.domain.readables
  "Assembles the cross-table readable catalog: every readable (article, paper,
   newsletter issue) normalized to one shape, joined to its source affiliation
   and its authors. This is the shared library; the per-user reading queue
   (reader.domain.reading) is drawn from it. The join happens in Clojure over a
   handful of plain CRUD reads — the data is small and the result is far
   easier to test as a value than as SQL. Note this is the readable's *own*
   affiliation (its publisher/source); an author's affiliations are a
   separate relation, surfaced on the author page, not here."
  (:require [clojure.string :as str]
            [reader.db.crud :as crud]))

(defn- article-url
  "An article's external original — what a browse page links out to."
  [row]
  (:articles/canonical-url row))

(defn- paper-url
  "A paper's external original: its DOI page, else its arXiv abstract page, else
   nil (a placeholder not yet resolved to either)."
  [{:papers/keys [doi arxiv-id]}]
  (cond doi      (str "https://doi.org/" doi)
        arxiv-id (str "https://arxiv.org/abs/" arxiv-id)))

(def ^:private readable-types
  "Per readable table: the app-facing type keyword, the string stored in
   `authorships.readable_type`, the column holding the display title, and a
   `url-fn` deriving the external original (nil for newsletter issues — private
   inbound mail readable only through the owner-scoped reader view)."
  {:articles          {:type :article          :type-str "article"          :title-key :articles/title              :url-fn article-url}
   :papers            {:type :paper            :type-str "paper"            :title-key :papers/title               :url-fn paper-url}
   :newsletter-issues {:type :newsletter-issue :type-str "newsletter_issue" :title-key :newsletter-issues/subject  :url-fn (constantly nil)}})

(def ^:private type->table
  "The normalized item's `:type` keyword -> the table that backs it."
  (into {} (map (fn [[table {:keys [type]}]] [type table])) readable-types))

(defn- index-by [k rows]
  (into {} (map (juxt k identity)) rows))

(defn- author-ref [{:authors/keys [name slug]}]
  {:name name :slug slug})

(defn- source-ref [{:affiliations/keys [name slug type]}]
  {:name name :slug slug :type type})

(defn- normalize
  "One readable row -> the common item shape, joined to source + authors."
  [table row {:keys [affs-by-id authors-by-id authorships-by-readable]}]
  (let [{:keys [type type-str title-key url-fn]} (readable-types table)
        id     (get row (keyword (name table) "id"))
        aff-id (get row (keyword (name table) "affiliation-id"))]
    {:type    type
     :table   table
     :id      id
     :title   (get row title-key)
     :url     (url-fn row)
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
   and authors they reference — so the per-user queue (reader.domain.reading) that
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

;; ── browse views: what an author published, and what a source published ──────
;; Both cover articles + papers only. Newsletter issues are private inbound mail
;; with no public original — surfacing them on a global browse page would leak a
;; subject line, so they're deliberately excluded here (the per-user queue still
;; shows them, owner-scoped). The author<->source "published in" edge is derived
;; from these reads, not stored — see sources-of / contributors-of.

(defn by-author
  "Every article or paper `author-id` is credited on, normalized and joined to its
   source and co-authors. Newsletter issues are excluded (see the section comment).
   An author's 'published in' sources derive from this read via `sources-of`."
  [ds author-id]
  (let [ships (->> (crud/find-many ds :authorships {:author-id author-id})
                   (filter (comp #{"article" "paper"} :authorships/readable-type)))
        ids   (update-vals (group-by :authorships/readable-type ships)
                           #(distinct (map :authorships/readable-id %)))]
    (catalog-from-rows ds
                       {:articles (crud/find-in ds :articles :id (get ids "article"))
                        :papers   (crud/find-in ds :papers   :id (get ids "paper"))})))

(defn by-source
  "Every article or paper published by `affiliation-id`, normalized and joined to
   its source and authors. Newsletter issues are excluded (see the section
   comment). A source's contributing authors derive from this read via
   `contributors-of`."
  [ds affiliation-id]
  (catalog-from-rows ds
                     {:articles (crud/find-many ds :articles {:affiliation-id affiliation-id})
                      :papers   (crud/find-many ds :papers   {:affiliation-id affiliation-id})}))

(defn sources-of
  "The distinct sources across normalized `items`, ordered case-insensitively by
   name — an author's 'published in' set, derived from `by-author`."
  [items]
  (->> items (keep :source) distinct (sort-by (comp str/lower-case :name)) vec))

(defn contributors-of
  "The distinct authors across normalized `items`, ordered case-insensitively by
   name — a source's contributing authors, derived from `by-source`."
  [items]
  (->> items (mapcat :authors) distinct (sort-by (comp str/lower-case :name)) vec))
