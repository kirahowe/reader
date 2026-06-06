(ns reader.authors
  "Author domain logic. `sort-name` (the \"Last, First\" collation key) is
   optional; when omitted, `create!` derives it conservatively, bailing to
   nil on anything ambiguous. A nil sort-name falls back to display-name
   sorting — a fine default, where a confidently wrong key is not."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [reader.db.crud :as crud]))

;; Tokens that make a two-word name *not* a plain "First Last": a leading
;; article ("The Atlantic"), a name particle ("Van Halen", "de Beauvoir"),
;; or a generational/credential suffix ("Smith Jr"). Any of these and we
;; decline to derive — the surname is genuinely ambiguous.
(def ^:private leading-articles #{"the" "a" "an"})
(def ^:private particles
  #{"van" "von" "der" "den" "de" "del" "della" "da" "di" "du" "dos" "das"
    "la" "le" "el" "al" "bin" "ibn" "ben" "st" "st."})
(def ^:private suffixes
  #{"jr" "jr." "sr" "sr." "ii" "iii" "iv" "phd" "md" "esq"})

(defn- capitalized? [^String tok]
  (Character/isUpperCase (.charAt tok 0)))

(defn derive-sort-name
  "Best-effort \"Last, First\" collation key from a display name, or nil
   when the name is anything but an unambiguous two-part \"First Last\".
   Conservative by design — see the namespace docstring."
  [display-name]
  (when (some? display-name)
    (let [tokens (str/split (str/trim display-name) #"\s+")]
      (when (= 2 (count tokens))
        (let [[first-tok last-tok] tokens
              lo-first (str/lower-case first-tok)
              lo-last  (str/lower-case last-tok)]
          (when (and (not (leading-articles lo-first))
                     (not (particles lo-first))
                     (not (suffixes lo-first))
                     (not (suffixes lo-last))
                     (capitalized? first-tok)
                     (capitalized? last-tok))
            (str last-tok ", " first-tok)))))))

(defn create!
  "Insert an author. When the caller omits `:sort-name`, derive it from
   `:name` via `derive-sort-name`. An explicitly supplied `:sort-name`
   (even nil) always wins: a manual override beats the heuristic."
  [ds attrs]
  (crud/create! ds :authors
                (cond-> attrs
                  (not (contains? attrs :sort-name))
                  (assoc :sort-name (derive-sort-name (:name attrs))))))

(defn list-sorted
  "All authors ordered by collation key, falling back to the display name
   when `sort-name` is NULL. This COALESCE is the canonical author
   ordering — the UI should sort on the same expression.

   TODO(ui): surface a manual sort-name override and flag NULL sort-names
   so the user can populate them for nicer surname-first browsing."
  [ds]
  (jdbc/execute! ds
                 (sql/format {:select   [:*]
                              :from     [:authors]
                              :order-by [[[:coalesce :sort-name :name] :asc]]})
                 crud/opts))

(defn by-slug
  "The author with this URL slug, or nil."
  [ds slug]
  (crud/find-1 ds :authors {:slug slug}))

(defn affiliations-of
  "The outlets `author-id` writes for, joined to the affiliation rows and
   flattened to {:name :slug :type :role :primary?}, primary first then by
   name. Two small reads joined in Clojure — keeps the result-set builder
   off a multi-table join."
  [ds author-id]
  (let [affs (into {} (map (juxt :affiliations/id identity))
                   (crud/find-many ds :affiliations))]
    (->> (crud/find-many ds :author-affiliations {:author-id author-id})
         (map (fn [link]
                (let [a (get affs (:author-affiliations/affiliation-id link))]
                  {:name     (:affiliations/name a)
                   :slug     (:affiliations/slug a)
                   :type     (:affiliations/type a)
                   :role     (:author-affiliations/role link)
                   :primary? (:author-affiliations/is-primary link)})))
         (sort-by (juxt (complement :primary?) :name))
         vec)))
