(ns reader.papers
  "Paper domain. `detect` classifies a pasted string as an arXiv or DOI
   reference (or nil) — the entry point for adding a paper by link. arXiv is
   checked first; a DOI is the fallback, including arXiv's own
   10.48550/arXiv.<id> DOI (which the ingest job maps back to an arXiv id for the
   reflowable HTML body). Pure."
  (:require [clojure.string :as str]))

(def ^:private arxiv-new #"(?i)(\d{4}\.\d{4,5})(?:v\d+)?")
(def ^:private arxiv-old #"(?i)([a-z][a-z\-]*(?:\.[a-z][a-z\-]*)?/\d{7})(?:v\d+)?")
(def ^:private arxiv-whole #"(?i)(?:\d{4}\.\d{4,5}|[a-z][a-z\-]*(?:\.[a-z][a-z\-]*)?/\d{7})(?:v\d+)?")
(def ^:private doi-re #"(?i)(10\.\d{4,9}/[^\s\"<>]+)")

(defn- arxiv-id
  "The bare arXiv id (version stripped, case preserved) found in `s`, or nil."
  [s]
  (or (some-> (re-find arxiv-new s) second)
      (some-> (re-find arxiv-old s) second)))

(defn- arxiv-ref?
  "True when `s` is an arXiv reference: an arxiv.org/ar5iv URL, an arXiv: prefix,
   or a bare arXiv id."
  [s]
  (boolean (or (re-find #"(?i)\b(?:ar5iv|arxiv\.org)\b" s)
               (re-find #"(?i)^arxiv:" s)
               (re-matches arxiv-whole s))))

(defn detect
  "A pasted string -> {:kind :arxiv :id <id>} (case preserved) |
   {:kind :doi :id <lowercased doi>} | nil."
  [s]
  (when-let [s (some-> s str/trim not-empty)]
    (cond
      (and (arxiv-ref? s) (arxiv-id s)) {:kind :arxiv :id (arxiv-id s)}
      (re-find doi-re s)                {:kind :doi :id (str/lower-case (second (re-find doi-re s)))}
      :else                             nil)))
