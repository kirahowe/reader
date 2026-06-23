(ns reader.domain.affiliations
  "Affiliation domain logic. Affiliations are publications/outlets — the
   `source` side of a readable and the place side of an author_affiliation."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [reader.db.crud :as crud]
            [reader.util.slug :as slug]))

(defn find-or-create!
  "Find an affiliation by the slug derived from `name`, or create it with a
   conservative default type of \"other\" (the user can reclassify later) and the
   optional homepage `url`. Idempotent and race-safe via upsert, so it composes
   inside an ingest transaction. An existing row keeps its type and url; the url is
   set only on first insert (mirroring authors/find-or-create!)."
  ([ds name] (find-or-create! ds name nil))
  ([ds name url]
   (crud/upsert! ds :affiliations
                 (cond-> {:name name :slug (slug/slugify name) :type "other"}
                   url (assoc :url url))
                 [:slug]
                 {:updated-at [:now]})))

(defn link-author!
  "Idempotently link `author-id` to `affiliation-id` in author_affiliations — the
   institutional-affiliation edge (an author affiliated with an institution, from
   the papers/OpenAlex path). The editorial 'published in' relationship is derived,
   not stored here. Find-then-create rather than upsert: the unique key is NULLS NOT
   DISTINCT over (author, affiliation, starts_on), and we only ever write the
   open-ended (starts_on NULL) link, so a re-run finds the existing row. Returns the
   link row (existing or new). Pass a transaction to compose inside a larger write."
  [ds author-id affiliation-id]
  (or (crud/find-1 ds :author-affiliations {:author-id author-id :affiliation-id affiliation-id})
      (crud/create! ds :author-affiliations
                    {:author-id author-id :affiliation-id affiliation-id :role "author"})))

(defn resolve!
  "Upsert an affiliation/institution by stable identity — ROR → OpenAlex id →
   name-slug — filling newly-known fields without clobbering. `m`: :name
   (required), optional :type (default \"other\"), :ror :openalex-id :url. Returns
   the row; pass a transaction for atomicity."
  [tx {:keys [name type ror openalex-id url] :or {type "other"}}]
  (crud/resolve-entity! tx :affiliations
                        {:id-keys  [:ror :openalex-id]
                         :slug-key :slug
                         :attrs    (cond-> {:name name :slug (slug/slugify name) :type type}
                                     ror         (assoc :ror ror)
                                     openalex-id (assoc :openalex-id openalex-id)
                                     url         (assoc :url url))}))

(defn list-sorted
  "Affiliations that are sources — publications and venues you read from — ordered
   case-insensitively by name. Institutions (the academic-affiliation sense) are
   excluded: they're reached from an author's page, not browsed as sources."
  [ds]
  (jdbc/execute! ds
                 (sql/format {:select   [:*]
                              :from     [:affiliations]
                              :where    [:<> :type "institution"]
                              :order-by [[[:lower :name] :asc]]})
                 crud/opts))

(defn by-slug
  "The affiliation with this URL slug, or nil."
  [ds slug]
  (crud/find-1 ds :affiliations {:slug slug}))
