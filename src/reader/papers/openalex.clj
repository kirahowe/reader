(ns reader.papers.openalex
  "OpenAlex client: look up a work by DOI (including arXiv's own
   10.48550/arXiv.<id> DOI) and normalize it into the entity graph the
   :extract-paper job upserts — title/abstract/date, the venue, and authors with
   their ORCID + institutions (ROR, country, OpenAlex ids). One free call does
   the whole fan-out. The fetch fn is injected (tests stub the JSON); the
   normalization is pure. ORCID/ROR are kept in OpenAlex's canonical URL form."
  (:require [charred.api :as json]
            [clojure.string :as str])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
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

(defn work-url [ref]
  ;; DOIs carry slashes that OpenAlex wants unencoded in the path.
  (str "https://api.openalex.org/works/doi:" (doi-for ref)))

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

(defn fetch
  "Fetch + normalize the OpenAlex work for `ref` via `fetch-fn` (a url -> body
   string, or nil on miss/non-200). Returns the normalized graph, or nil."
  [fetch-fn ref]
  (when-let [body (fetch-fn (work-url ref))]
    (let [work (try (parse-json body) (catch Exception _ nil))]
      (when (and (map? work) (get work "id"))
        (normalize work)))))

(defn http-get
  "Default `fetch-fn`: GET `url`, returning the body on 200, else nil."
  [url]
  (let [req  (-> (HttpRequest/newBuilder (URI/create url))
                 (.header "User-Agent" "Reader/1.0 (https://themiscellany.app)")
                 (.GET)
                 (.build))
        resp (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/ofString))]
    (when (= 200 (.statusCode resp)) (.body resp))))
