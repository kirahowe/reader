(ns reader.papers.arxiv
  "Fetch a paper's reflowable HTML body from arXiv — arXiv's native HTML, with
   ar5iv as the back-catalog fallback — and sanitize it for safe in-reader
   rendering. Keeps MathML so equations render natively in the browser; strips
   scripts and the source stylesheet/classes so the body reflows under our own
   prose CSS (mobile-friendly, unlike an embedded PDF). The fetch fn is injected;
   sanitization is pure.

   `fetch-metadata` is the metadata fallback for arXiv papers OpenAlex doesn't
   have (notably the back catalog — arXiv's DataCite DOIs aren't all indexed):
   title/abstract/date + author *names* from the arXiv Atom API, in the same
   graph shape reader.papers.openalex/normalize returns."
  (:require [clojure.string :as str])
  (:import (java.time Instant)
           (org.jsoup Jsoup)
           (org.jsoup.nodes Element)
           (org.jsoup.parser Parser)
           (org.jsoup.safety Cleaner Safelist)))

(def ^:private mathml-tags
  ["math" "semantics" "annotation" "annotation-xml" "mrow" "mi" "mo" "mn" "ms"
   "mtext" "mspace" "msup" "msub" "msubsup" "mfrac" "msqrt" "mroot" "mstyle"
   "merror" "mpadded" "mphantom" "menclose" "mtable" "mtr" "mtd" "mlabeledtr"
   "munder" "mover" "munderover" "mmultiscripts" "mprescripts" "none" "mglyph"
   "maction"])

;; `definitionurl` is deliberately *not* here: it's the one URL-bearing MathML
;; attribute, and Jsoup doesn't protocol-check attributes it isn't told are URLs,
;; so a `definitionurl="javascript:…"` would survive `:all`. It's a deprecated
;; attribute no renderer acts on, so dropping it costs nothing.
(def ^:private mathml-attrs
  ["mathvariant" "display" "displaystyle" "scriptlevel" "stretchy" "fence"
   "separator" "accent" "accentunder" "movablelimits" "columnspan" "rowspan"
   "columnalign" "rowalign" "open" "close" "encoding"
   "linethickness" "notation" "lspace" "rspace" "mathsize"])

(defn- math-safelist ^Safelist []
  (doto (Safelist/relaxed)
    (.addTags (into-array String mathml-tags))
    (.addAttributes ":all" (into-array String mathml-attrs))
    ;; Keep element ids so the paper's own cross-references (sections, equations,
    ;; citations, figures) have in-page targets to scroll to. `class` stays
    ;; stripped — the body reflows under our prose CSS, not arXiv's.
    (.addAttributes ":all" (into-array String ["id"]))))

(defn sanitize
  "Clean `html` for in-reader rendering, preserving MathML and element ids.
   Resource urls (images) resolve to absolute against `base-uri`, but a same-
   document anchor (`#frag`, which Jsoup would otherwise absolutize to
   `base-uri#frag`) is kept relative so in-paper navigation scrolls within the
   reader instead of bouncing the user out to arXiv."
  [html base-uri]
  (when (not-empty html)
    (let [clean    (.clean (Cleaner. (math-safelist)) (Jsoup/parse html base-uri))
          self-doc (str base-uri "#")]
      (doseq [^Element a (.select clean "a[href]")]
        (let [href (.attr a "href")]
          (when (str/starts-with? href self-doc)
            (.attr a "href" (subs href (count base-uri))))))
      (.html (.body clean)))))

(defn- html-url [arxiv-id] (str "https://arxiv.org/html/" arxiv-id))
(defn- ar5iv-url [arxiv-id] (str "https://ar5iv.labs.arxiv.org/html/" arxiv-id))
(defn- query-url [arxiv-id] (str "https://export.arxiv.org/api/query?id_list=" arxiv-id))

(defn- ok-body
  "The text body of a completed 200 response, else nil. `resp` is a
   reader.http/request! result {:status :body :error}."
  [{:keys [status body]}]
  (when (= 200 status) (not-empty body)))

(defn fetch-body
  "The sanitized reflowable HTML body for `arxiv-id` — arXiv's native HTML, else
   ar5iv — via `request-fn` (url -> a promise of reader.http/request!'s result).
   Tries arXiv first and only hits ar5iv if arXiv has no HTML. nil when neither
   serves it."
  [request-fn arxiv-id]
  (some (fn [url] (when-let [h (ok-body @(request-fn url))] (sanitize h url)))
        [(html-url arxiv-id) (ar5iv-url arxiv-id)]))

(defn- el-text [^Element parent css]
  (some-> (.selectFirst parent css) .text str/trim not-empty))

(defn- parse-instant [s]
  (try (Instant/parse s) (catch Exception _ nil)))

(defn- metadata-graph
  "The arXiv Atom feed `xml` -> the graph shape reader.papers.openalex/parse-graph
   returns (so the job consumes it identically): title/abstract/date and author
   *names* (no ORCID or institutions; OpenAlex is the source for those), venue
   arXiv. nil when the feed has no entry (unknown id)."
  [xml]
  (let [entry (.selectFirst (Jsoup/parse xml "" (Parser/xmlParser)) "entry")]
    (when-let [title (some-> entry (el-text "title"))]
      {:title     title
       :abstract  (el-text entry "summary")
       :published (some-> (el-text entry "published") parse-instant)
       :venue     {:name "arXiv" :type "repository"}
       :authors   (mapv (fn [n] {:name (str/trim (.text ^Element n))})
                        (.select entry "author > name"))})))

(defn fetch-metadata
  "Fallback metadata for `arxiv-id` from the arXiv Atom API, via `request-fn` (url
   -> a promise of reader.http/request!'s result). nil when the paper isn't found."
  [request-fn arxiv-id]
  (some-> (ok-body @(request-fn (query-url arxiv-id))) metadata-graph))
