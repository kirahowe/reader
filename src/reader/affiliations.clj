(ns reader.affiliations
  "Affiliation domain logic. Affiliations are publications/outlets — the
   `source` side of a readable and the place side of an author_affiliation."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [reader.db.crud :as crud]))

(defn list-sorted
  "All affiliations ordered case-insensitively by name — the canonical
   ordering for the sources index."
  [ds]
  (jdbc/execute! ds
                 (sql/format {:select   [:*]
                              :from     [:affiliations]
                              :order-by [[[:lower :name] :asc]]})
                 crud/opts))
