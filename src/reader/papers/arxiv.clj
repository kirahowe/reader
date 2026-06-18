(ns reader.papers.arxiv
  "Fetch a paper's reflowable HTML body from arXiv — arXiv's native HTML, with
   ar5iv as the back-catalog fallback — and sanitize it for safe in-reader
   rendering. Keeps MathML so equations render natively in the browser; strips
   scripts and the source stylesheet/classes so the body reflows under our own
   prose CSS (mobile-friendly, unlike an embedded PDF). The fetch fn is injected;
   sanitization is pure."
  (:require [clojure.string :as str])
  (:import (org.jsoup Jsoup)
           (org.jsoup.safety Safelist)))

(def ^:private mathml-tags
  ["math" "semantics" "annotation" "annotation-xml" "mrow" "mi" "mo" "mn" "ms"
   "mtext" "mspace" "msup" "msub" "msubsup" "mfrac" "msqrt" "mroot" "mstyle"
   "merror" "mpadded" "mphantom" "menclose" "mtable" "mtr" "mtd" "mlabeledtr"
   "munder" "mover" "munderover" "mmultiscripts" "mprescripts" "none" "mglyph"
   "maction"])

(def ^:private mathml-attrs
  ["mathvariant" "display" "displaystyle" "scriptlevel" "stretchy" "fence"
   "separator" "accent" "accentunder" "movablelimits" "columnspan" "rowspan"
   "columnalign" "rowalign" "open" "close" "encoding" "definitionurl"
   "linethickness" "notation" "lspace" "rspace" "mathsize"])

(defn- math-safelist ^Safelist []
  (doto (Safelist/relaxed)
    (.addTags (into-array String mathml-tags))
    (.addAttributes ":all" (into-array String mathml-attrs))))

(defn sanitize
  "Clean `html` (resolving relative urls against `base-uri`), preserving MathML."
  [html base-uri]
  (when (not-empty html)
    (Jsoup/clean html base-uri (math-safelist))))

(defn fetch-body
  "The sanitized reflowable HTML body for `arxiv-id` — arXiv's native HTML, else
   ar5iv — via `fetch-fn` (url -> body string, or nil on miss). nil when neither
   serves it."
  [fetch-fn arxiv-id]
  (some (fn [url] (when-let [h (fetch-fn url)] (sanitize h url)))
        [(str "https://arxiv.org/html/" arxiv-id)
         (str "https://ar5iv.labs.arxiv.org/html/" arxiv-id)]))
