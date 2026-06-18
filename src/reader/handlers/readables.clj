(ns reader.handlers.readables
  "Ingest UI handlers: paste a URL to import (POST /readables) and the poll
   endpoint that re-renders one importing row (GET /queue/:id/row). Lean glue —
   the work lives in reader.ingest."
  (:require [integrant.core :as ig]
            [reader.ingest :as ingest]
            [reader.papers :as papers]
            [reader.reading :as reading]
            [reader.ui.pages.home :as home]
            [reader.web.request :as request]
            [reader.web.response :as response]))

(defn- importing
  "Common response for a started ingest: HTMX swaps the new placeholder row in; a
   non-HTMX POST falls back to a full reload (the row appears on the re-render)."
  [req queue-item label]
  (if (get-in req [:headers "hx-request"])
    (response/fragment (home/importing-row (:queue-items/id queue-item) label))
    (response/see-other "/")))

(defmethod ig/init-key :reader.handlers.readables/create [_ {:keys [datasource]}]
  (fn [req]
    (let [input (get-in req [:params "url"])]
      (if-let [ref (papers/detect input)]
        ;; An arXiv/DOI reference becomes a paper (its own ingest path); anything
        ;; else that's a valid http(s) URL is fetched as an article.
        (let [{:keys [queue-item paper]} (papers/start! datasource (:user-id req) ref)]
          (importing req queue-item (:papers/title paper)))
        (if-let [url (ingest/normalize-url input)]
          (let [{:keys [queue-item]} (ingest/start! datasource (:user-id req) url)]
            (importing req queue-item url))
          (response/see-other "/"))))))

(defn- ingest-status
  "Ingest status of a queued readable, dispatched by type: articles and papers
   import asynchronously (poll their job), newsletter issues only enter the queue
   once written, so they're ready."
  [ds {:keys [type id]}]
  (case type
    :article (ingest/status ds id)
    :paper   (papers/status ds id)
    :done))

(defmethod ig/init-key :reader.handlers.readables/row [_ {:keys [datasource]}]
  (fn [req]
    (let [id   (request/path-uuid req)
          item (and id (reading/queue-item datasource (:user-id req) id))]
      (if-not item
        ;; The item is gone (archived/removed, or never visible to this user):
        ;; a 200 empty fragment removes the stale row and stops the poll. A 404
        ;; would make HTMX keep polling, and not-found rendered the whole page
        ;; chrome into a single <li>.
        (response/fragment nil)
        (response/fragment
         (case (ingest-status datasource item)
           :done   (home/item item)
           :failed (home/failed-row id (:title item))
           (home/importing-row id (:title item))))))))
