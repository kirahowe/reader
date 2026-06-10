(ns reader.slug
  "URL slug derivation, shared by the domains that need one (articles, authors,
   affiliations). Not unique by construction — lowercased, non-alphanumeric runs
   collapsed to a single hyphen, hyphens trimmed off the ends. Callers that need
   uniqueness enforce it at the database."
  (:require [clojure.string :as str]))

(defn slugify [s]
  (-> (or s "")
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))
