(ns reader.articles
  "Article domain logic. The form helpers here are pure so the HTTP handler
   stays glue: it parses request params to a clean attrs map, validates it,
   and only then writes. `create!` is the single write edge."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [reader.db.crud :as crud]))

(defn slugify
  "A URL slug from a title: lowercased, non-alphanumeric runs collapsed to a
   single hyphen, hyphens trimmed from the ends. Not unique by design —
   `articles.slug` carries no uniqueness constraint (only `canonical_url`
   does)."
  [s]
  (-> (or s "")
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn- blank->nil [s]
  (when-not (str/blank? s) (str/trim s)))

(defn parse-form
  "Ring's string-keyed params -> a clean attrs map. Required fields (title,
   canonical-url) are always present, nil when absent/blank; optional fields
   are included only when supplied. affiliation-id stays a string here and is
   parsed to a UUID after validation."
  [params]
  (let [v #(blank->nil (get params %))]
    (cond-> {:title         (v "title")
             :canonical-url (v "canonical-url")}
      (v "abstract")       (assoc :abstract (v "abstract"))
      (v "affiliation-id") (assoc :affiliation-id (v "affiliation-id")))))

(def ^:private uuid-re
  #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(def ^:private form-schema
  [:map
   [:title [:string {:min 1 :error/message "Title is required."}]]
   [:canonical-url [:and
                    [:string {:min 1 :error/message "URL is required."}]
                    [:re {:error/message "Enter a full URL starting with http:// or https://"}
                     #"(?i)^https?://"]]]
   [:abstract {:optional true} [:maybe :string]]
   [:affiliation-id {:optional true} [:re {:error/message "Unknown source."} uuid-re]]])

(defn validate
  "nil when `attrs` is a well-formed article submission, otherwise a
   humanized {field [messages]} error map suitable for re-rendering the form."
  [attrs]
  (when-not (m/validate form-schema attrs)
    (me/humanize (m/explain form-schema attrs))))

(defn create!
  "Parse, validate, and insert an article. Returns {:article row} on success,
   or {:errors m} for invalid input or an expected DB-level rejection: a
   canonical URL that already exists, or an affiliation-id with no matching
   source (a well-formed but stale/forged id slips past validation and trips
   the FK)."
  [ds params]
  (let [attrs (parse-form params)]
    (if-let [errors (validate attrs)]
      {:errors errors}
      (try
        {:article (crud/create! ds :articles
                                (-> attrs
                                    (assoc :slug (slugify (:title attrs)))
                                    (cond-> (:affiliation-id attrs)
                                      (update :affiliation-id parse-uuid))))}
        (catch java.sql.SQLException e
          (case (.getSQLState e)
            "23505" {:errors {:canonical-url ["An article with that URL already exists."]}}
            "23503" {:errors {:affiliation-id ["Unknown source."]}}
            (throw e)))))))
