(ns reader.papers.openalex
  "OpenAlex client: look up a work by DOI (including arXiv's own
   10.48550/arXiv.<id> DOI) and normalize it into the entity graph the
   :extract-paper job upserts — title/abstract/date, the venue, and authors with
   their ORCID + institutions (ROR, country, OpenAlex ids). One free call does
   the whole fan-out. This namespace is pure: `work-url` builds the request URL
   and `parse-graph` turns a response body into the graph; the fetch itself is the
   caller's (reader.papers, via reader.http). ORCID/ROR are kept in OpenAlex's
   canonical URL form."
  (:require [charred.api :as json]
            [clojure.string :as str])
  (:import (java.net URI)
           (java.time LocalDate ZoneOffset)))

;; String keys: abstract_inverted_index keys are arbitrary words, so don't
;; keywordize them.
(def ^:private parse-json (json/parse-json-fn {}))

(defn doi-for
  "The DOI to look up in OpenAlex for a detected ref: a :doi ref as-is, an :arxiv
   ref via its 10.48550/arXiv.<id> DOI."
  [{:keys [kind id]}]
  (case kind
    :doi   id
    :arxiv (str "10.48550/arxiv." (str/lower-case id))))

(defn work-url
  "The OpenAlex works endpoint for `ref`'s DOI. The DOI keeps its slashes (OpenAlex
   matches on the literal path), but URI percent-encodes anything else unsafe in a
   path segment — so a crafted DOI can't smuggle in a query/fragment and reshape
   the request."
  [ref]
  (str (URI. "https" "api.openalex.org" (str "/works/doi:" (doi-for ref)) nil)))

(defn- ->instant [date-str]
  (try (-> (LocalDate/parse date-str) (.atStartOfDay ZoneOffset/UTC) .toInstant)
       (catch Exception _ nil)))

(defn- reconstruct-abstract
  "OpenAlex stores abstracts as an inverted index {word -> [positions]}; rebuild
   the prose by placing each word at its position(s)."
  [inv]
  (when (map? inv)
    (-> (->> inv
             (mapcat (fn [[word positions]] (map (fn [p] [p word]) positions)))
             (sort-by first)
             (map second)
             (str/join " "))
        not-empty)))

(defn- institution [m]
  {:name        (get m "display_name")
   :openalex-id (get m "id")
   :ror         (get m "ror")
   :country     (get m "country_code")})

(defn- author [authorship]
  (let [a (get authorship "author")]
    {:name         (get a "display_name")
     :openalex-id  (get a "id")
     :orcid        (get a "orcid")
     :institutions (mapv institution (get authorship "institutions"))}))

(defn normalize
  "An OpenAlex work map (string keys) -> the entity graph, or nil."
  [work]
  (when (map? work)
    (let [src (get-in work ["primary_location" "source"])]
      {:title     (or (get work "title") (get work "display_name"))
       :abstract  (reconstruct-abstract (get work "abstract_inverted_index"))
       :published (some-> (get work "publication_date") ->instant)
       :venue     (when src {:name        (get src "display_name")
                             :openalex-id (get src "id")
                             :type        (get src "type")})
       :authors   (mapv author (get work "authorships"))})))

(defn parse-graph
  "An OpenAlex works response `body` (a JSON string) -> the normalized entity
   graph, or nil when it isn't a usable work (nil/unparseable body, or no id —
   OpenAlex returns a non-work shape for an unknown DOI)."
  [body]
  (when body
    (let [work (try (parse-json body) (catch Exception _ nil))]
      (when (and (map? work) (get work "id"))
        (normalize work)))))
