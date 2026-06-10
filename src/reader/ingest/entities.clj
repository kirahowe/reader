(ns reader.ingest.entities
  "The default implementation of the entity-extraction seam: derive first-class
   authors + affiliation from the page's declared metadata (the signals/fields
   reader.ingest.extract produced). Returns a reader.ingest.schema/EntityResult.

   This is the deterministic, zero-cost path. A future LLM-backed extractor
   satisfies the *same* contract and is wired in by config when the eval
   dashboard shows the metadata path leaving real authors on the table — this
   namespace then becomes the fallback, unchanged. Everything here is pure."
  (:require [clojure.string :as str]
            [reader.ingest.extract :as extract]))

(def ^:private confidence-by-source
  {:json-ld 0.95 :og 0.9 :meta 0.7 :rel-author 0.7 :domain 0.5 :heuristic 0.5})

(defn- conf-of [source] (get confidence-by-source source 0.5))

(defn- blank->nil [s]
  (when (and (string? s) (not (str/blank? s))) (str/trim s)))

(defn- author-url
  "An author node's homepage: its `url`, else the first `sameAs`, when http(s)."
  [a]
  (when (map? a)
    (some (fn [k] (let [s (extract/ld-text (get a k))]
                    (when (and s (re-find #"(?i)^https?://" s)) s)))
          ["url" "sameAs"])))

(defn- ld-author-entries
  "{:name :url?} maps from the first JSON-LD block that declares any authors;
   `:url` is the author node's url/sameAs when present and http(s)."
  [blocks]
  (some (fn [obj]
          (let [auth    (get obj "author")
                entries (->> (if (sequential? auth) auth [auth])
                             (keep (fn [a]
                                     (when-let [n (extract/ld-text a)]
                                       (let [u (author-url a)]
                                         (cond-> {:name n} u (assoc :url u))))))
                             vec)]
            (when (seq entries) entries)))
        blocks))

(defn- split-byline
  "Split a byline string into individual names, dropping a leading \"By \" and
   separating on commas, semicolons, ampersands, and the word \"and\"."
  [s]
  (->> (str/split (str/replace s #"(?i)^\s*by\s+" "")
                  #"\s*(?:,|;|&|\band\b)\s*")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- og-author
  "og:article:author, but only when it's a name — many sites put a profile URL
   there, which is a link, not a byline."
  [og]
  (let [v (blank->nil (get og "article:author"))]
    (when (and v (not (re-find #"(?i)^https?://" v))) v)))

(defn- mk-authors
  "Author records from {:name :url?} entries, capped to the EntityResult max."
  [entries source]
  (mapv (fn [{:keys [name url]}]
          (cond-> {:name name :source source :confidence (conf-of source)}
            url (assoc :url url)))
        (take 50 entries)))

(defn- names->entries [names] (map (fn [n] {:name n}) names))

(defn- resolve-authors [{:keys [signals]}]
  (let [{:keys [json-ld meta og]} signals]
    (or (when-let [entries (seq (ld-author-entries json-ld))] (mk-authors entries :json-ld))
        (when-let [byline (blank->nil (get meta "author"))] (mk-authors (names->entries (split-byline byline)) :meta))
        (when-let [byline (og-author og)] (mk-authors (names->entries (split-byline byline)) :og))
        [])))

(defn- resolve-affiliation
  "The publication, lifted from the already-resolved site-name field (keeping
   its provenance). A model-backed seam could infer this from the body instead;
   the contract is the same."
  [context]
  (let [{:keys [value source]} (get-in context [:fields :site-name])]
    (when (blank->nil value)
      {:name value :source source :confidence (conf-of source)})))

(defn- overall [authors affiliation]
  (let [cs (remove nil? (cons (:confidence affiliation) (map :confidence authors)))]
    (if (seq cs) (double (/ (reduce + cs) (count cs))) 0.0)))

(defn from-metadata
  "Entity extraction from declared metadata — the default seam implementation."
  [context]
  (let [authors     (resolve-authors context)
        affiliation (resolve-affiliation context)]
    {:authors            authors
     :affiliation        affiliation
     :overall-confidence (overall authors affiliation)}))
