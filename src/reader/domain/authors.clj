(ns reader.domain.authors
  "Author domain logic. `sort-name` (the \"Last, First\" collation key) is
   optional; when omitted, `create!` derives it conservatively, bailing to
   nil on anything ambiguous. A nil sort-name falls back to display-name
   sorting — a fine default, where a confidently wrong key is not."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [reader.db.crud :as crud]
            [reader.util.slug :as slug]))

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

(defn find-or-create!
  "Find an author by the slug derived from `name`, or create one (deriving a
   sort-name and storing the optional homepage `url`). Idempotent and race-safe
   via upsert — usable inside a larger ingest transaction, where a plain insert
   + catch would abort the tx on conflict. Conservative: two people sharing a
   name collapse to one row; the user can split them later. An existing author
   keeps its stored url; the url is set only on first insert."
  ([ds name] (find-or-create! ds name nil))
  ([ds name url]
   (crud/upsert! ds :authors
                 (cond-> {:name name :slug (slug/slugify name) :sort-name (derive-sort-name name)}
                   url (assoc :url url))
                 [:slug]
                 {:updated-at [:now]})))

(defn resolve!
  "Upsert an author by stable identity — ORCID → OpenAlex id → name-slug —
   filling newly-known fields without clobbering existing ones. `m`: :name
   (required), optional :orcid :openalex-id :url. The slug is a URL handle derived
   from the name (disambiguated on collision), not the identity. Returns the row;
   pass a transaction for atomicity. Use for sourced entities (papers); the bare
   name-only paths can stay on `find-or-create!` (= the slug fallback)."
  [tx {:keys [name orcid openalex-id url]}]
  (crud/resolve-entity! tx :authors
                        {:id-keys  [:orcid :openalex-id]
                         :slug-key :slug
                         :attrs    (cond-> {:name name :slug (slug/slugify name)
                                            :sort-name (derive-sort-name name)}
                                     orcid       (assoc :orcid orcid)
                                     openalex-id (assoc :openalex-id openalex-id)
                                     url         (assoc :url url))}))

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

(defn institutions-of
  "The institutions `author-id` is affiliated with — the academic sense, sourced
   from papers (OpenAlex). Reads the author_affiliations links and keeps only those
   whose affiliation is an institution; the editorial 'published in' relationship
   is *derived* from an author's works, not stored here (see
   reader.domain.readables/by-author). Returns {:name :slug :primary?}, primary
   first then by name. Two small reads joined in Clojure — fetching only the
   affiliations this author links to."
  [ds author-id]
  (let [links (crud/find-many ds :author-affiliations {:author-id author-id})
        insts (into {} (comp (filter #(= "institution" (:affiliations/type %)))
                             (map (juxt :affiliations/id identity)))
                    (crud/find-in ds :affiliations :id (map :author-affiliations/affiliation-id links)))]
    (->> links
         (keep (fn [link]
                 (when-let [a (insts (:author-affiliations/affiliation-id link))]
                   {:name     (:affiliations/name a)
                    :slug     (:affiliations/slug a)
                    :primary? (:author-affiliations/is-primary link)})))
         (sort-by (juxt (complement :primary?) :name))
         vec)))
