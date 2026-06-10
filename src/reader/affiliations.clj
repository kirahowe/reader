(ns reader.affiliations
  "Affiliation domain logic. Affiliations are publications/outlets — the
   `source` side of a readable and the place side of an author_affiliation."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [reader.db.crud :as crud]
            [reader.slug :as slug]))

(defn find-or-create!
  "Find an affiliation by the slug derived from `name`, or create it with a
   conservative default type of \"other\" (the user can reclassify later).
   Idempotent and race-safe via upsert, so it composes inside an ingest
   transaction. An existing row keeps its type."
  [ds name]
  (crud/upsert! ds :affiliations
                {:name name :slug (slug/slugify name) :type "other"}
                [:slug]
                {:updated-at [:now]}))

(defn list-sorted
  "All affiliations ordered case-insensitively by name — the canonical
   ordering for the sources index."
  [ds]
  (jdbc/execute! ds
                 (sql/format {:select   [:*]
                              :from     [:affiliations]
                              :order-by [[[:lower :name] :asc]]})
                 crud/opts))
