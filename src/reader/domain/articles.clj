(ns reader.domain.articles
  "Article domain logic. `ingest-attrs` is the pure map from an extraction
   context to the article column map written when a placeholder is finalized."
  (:require [reader.util.slug :as slug]))

(defn ingest-attrs
  "Pure: the article column map to finalize a placeholder from an extraction
   context (reader.ingest.extract) plus a resolved affiliation id (nil ok).
   `now` (an Instant) is injected for `updated-at` — a HoneySQL [:now] would be
   jsonb-encoded by crud/update! (see reader.db.crud / reader.domain.reading). Title
   falls back to the url so the NOT NULL column is always satisfied."
  [extract affiliation-id now]
  (let [f     (:fields extract)
        b     (:body extract)
        v     (fn [k] (get-in f [k :value]))
        title (or (v :title) (:url extract))]
    (cond-> {:title             title
             :slug              (slug/slugify title)
             :body-html         (:html b)
             :word-count        (:word-count b)
             :reading-time-secs (:reading-time-secs b)
             :updated-at        now}
      affiliation-id    (assoc :affiliation-id affiliation-id)
      (v :lang)         (assoc :lang (v :lang))
      (v :published-at) (assoc :published-at (v :published-at)))))
