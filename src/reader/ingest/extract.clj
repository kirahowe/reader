(ns reader.ingest.extract
  "Pure extraction: a fetched HTML page -> a structured context the rest of the
   ingest pipeline consumes. Two concerns live here, both pure given the HTML
   string + final URL:

    - *metadata signals* — the raw JSON-LD / OpenGraph / <meta> / <title> /
      <link canonical> / lang the page declares, plus the deterministic
      `:fields` (title, published-at, lang, canonical-url, site-name) resolved
      from them with a `:source` provenance tag per field.
    - *body* — a sanitized reader-view body via Readability4J, with word count,
      reading time, and a confidence signal (the Tier-2 escalation cue).

   Entity interpretation (authors / affiliation as first-class records) is a
   separate, swappable step (reader.ingest.entities) that reads `:signals`
   here — so this namespace only reports what the page says, never decides the
   stored records. No network, no DB."
  (:require [charred.api :as json]
            [clojure.string :as str])
  (:import [org.jsoup Jsoup]
           [org.jsoup.safety Safelist]
           [net.dankito.readability4j Readability4J]
           [java.net URI]
           [java.time Instant OffsetDateTime LocalDate ZoneOffset]))

(def ^:private parse-ld
  "Precompiled charred parser (string keys — JSON-LD uses @-prefixed keys).
   Thread-safe per charred's guidance."
  (json/parse-json-fn {}))

(defn- blank->nil [s]
  (when (and (string? s) (not (str/blank? s))) (str/trim s)))

(defn ld-text
  "Resolve a JSON-LD textual value to a trimmed, non-blank string, or nil.
   JSON-LD lets a value be a bare string, a language-tagged value object
   ({\"@value\" ...}), a node carrying a \"name\", or an array of any of these —
   so this never assumes a string and never throws on a nested shape."
  [x]
  (cond
    (string? x)     (blank->nil x)
    (sequential? x) (some ld-text x)
    (map? x)        (or (ld-text (get x "@value")) (ld-text (get x "name")))
    :else           nil))

;; ── temporal parsing ────────────────────────────────────────────────────

(defn parse-temporal
  "Best-effort parse of a metadata date string to an Instant, trying a full
   instant, an offset datetime, then a bare date (interpreted at UTC midnight).
   nil — never an exception — on anything unparseable, per boundary-validation."
  [s]
  (when-let [s (blank->nil s)]
    (or (try (Instant/parse s) (catch Exception _ nil))
        (try (.toInstant (OffsetDateTime/parse s)) (catch Exception _ nil))
        (try (.toInstant (.atStartOfDay (LocalDate/parse s) ZoneOffset/UTC)) (catch Exception _ nil)))))

;; ── jsoup metadata signals ──────────────────────────────────────────────

(defn- attr-map
  "{key-attr -> val-attr} over every `selector` match (last write wins)."
  [doc selector key-attr val-attr]
  (into {} (for [el (.select doc selector)] [(.attr el key-attr) (.attr el val-attr)])))

(defn- ld-blocks
  "Every JSON-LD object on the page, flattening arrays and @graph. Malformed
   blocks are skipped, not fatal."
  [doc]
  (->> (.select doc "script[type=application/ld+json]")
       (mapcat (fn [el]
                 (try
                   (let [d (parse-ld (.data el))]
                     (cond
                       (vector? d)                                   d
                       (and (map? d) (vector? (get d "@graph")))     (get d "@graph")
                       (map? d)                                      [d]
                       :else                                         nil))
                   (catch Exception _ nil))))
       (filter map?)
       vec))

(def ^:private article-ld-types
  #{"Article" "NewsArticle" "BlogPosting" "Report" "TechArticle"
    "ScholarlyArticle" "ReportageNewsArticle"})

(defn- ld-types [obj]
  (let [t (get obj "@type")] (set (if (sequential? t) t [t]))))

(defn- article-ld
  "The JSON-LD object describing the article itself: the first with an
   article-ish @type, else the first carrying a headline or author."
  [blocks]
  (or (first (filter #(some article-ld-types (ld-types %)) blocks))
      (first (filter #(or (get % "headline") (get % "author")) blocks))))

(defn- ld-headline [a] (or (ld-text (get a "headline")) (ld-text (get a "name"))))

(defn- ld-publisher-name [a] (ld-text (get a "publisher")))

(defn- host->display
  "The host of `url` without a leading www. — a display fallback for site-name."
  [url]
  (some-> (try (.getHost (URI. url)) (catch Exception _ nil))
          (str/replace #"^www\." "")))

;; ── field resolution (value + provenance) ───────────────────────────────

(defn- pick
  "First [source value] with a non-blank value as {:value :source}, else
   {:value nil :source nil}. Precedence is the order of `pairs`."
  [pairs]
  (or (some (fn [[source v]] (when-let [v (blank->nil v)] {:value v :source source})) pairs)
      {:value nil :source nil}))

(defn- resolve-published [a og meta]
  (or (some (fn [[source raw]] (when-let [i (parse-temporal raw)] {:value i :source source}))
            [[:json-ld (get a "datePublished")]
             [:og      (get og "article:published_time")]
             [:meta    (or (get meta "article:published_time") (get meta "date")
                           (get meta "dc.date") (get meta "dcterms.date"))]])
      {:value nil :source nil}))

;; ── body extraction + confidence ────────────────────────────────────────

(defn- count-words [text]
  (if (str/blank? text) 0 (count (str/split (str/trim text) #"\s+"))))

(defn- reading-secs [wc] (long (Math/ceil (* (/ (double wc) 238.0) 60))))

(defn- link-density
  "Fraction of the body's text that sits inside anchors — high density flags a
   nav/index block mistaken for an article."
  [body-html body-text]
  (if (str/blank? body-html)
    0.0
    (let [link-text (.text (.select (Jsoup/parse body-html) "a"))]
      (double (/ (count link-text) (max 1 (count body-text)))))))

(defn- body-confidence
  "A 0–1 deterministic heuristic over the body signals. Low values are the
   cue that the deterministic body is suspect (the future Tier-2 trigger)."
  [{:keys [word-count link-density body-page-ratio]}]
  (cond
    (< word-count 50)                                       0.2
    (> link-density 0.5)                                    0.3
    (or (< body-page-ratio 0.05) (> body-page-ratio 0.98)) 0.4
    (< word-count 120)                                      0.6
    :else                                                   0.85))

(defn- extract-body [html url full-text]
  (let [article (.parse (Readability4J. url html))
        raw     (.getContent article)
        clean   (when raw (Jsoup/clean raw url (Safelist/relaxed)))
        text    (or (blank->nil (.getTextContent article)) "")
        wc      (count-words text)
        sig     {:word-count      wc
                 :link-density    (link-density clean text)
                 :body-page-ratio (double (/ (count text) (max 1 (count full-text))))}]
    {:html              clean
     :text              text
     :word-count        wc
     :reading-time-secs (reading-secs wc)
     :extractor         :readability4j
     :confidence        (body-confidence sig)
     :signals           sig}))

;; ── public ──────────────────────────────────────────────────────────────

(defn extract
  "Parse `html` (fetched from `url`) into the ingest context: `:signals` (raw
   metadata for the entity step), `:fields` (resolved title/published-at/lang/
   canonical-url/site-name, each {:value :source}), and `:body` (sanitized
   reader-view html/text + counts + confidence)."
  [html url]
  (let [doc       (Jsoup/parse html url)
        og        (attr-map doc "meta[property]" "property" "content")
        meta      (attr-map doc "meta[name]" "name" "content")
        title-tag (blank->nil (.title doc))
        html-lang (blank->nil (some-> doc (.selectFirst "html") (.attr "lang")))
        canon     (blank->nil (some-> doc (.selectFirst "link[rel=canonical]") (.attr "abs:href")))
        blocks    (ld-blocks doc)
        a         (article-ld blocks)
        full-text (.text doc)]
    {:url     url
     :signals {:json-ld blocks :og og :meta meta
               :title-tag title-tag :html-lang html-lang :canonical canon}
     :fields  {:title         (pick [[:json-ld (ld-headline a)] [:og (get og "og:title")] [:title-tag title-tag]])
               :published-at  (resolve-published a og meta)
               :lang          (pick [[:html-lang html-lang] [:json-ld (get a "inLanguage")] [:og (get og "og:locale")]])
               :canonical-url (pick [[:canonical canon] [:og (get og "og:url")] [:url url]])
               :site-name     (pick [[:og (get og "og:site_name")] [:json-ld (ld-publisher-name a)] [:domain (host->display url)]])}
     :body    (extract-body html url full-text)}))
