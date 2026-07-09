(ns reader.handlers.readables
  "Ingest UI handlers: paste a URL to import (POST /readables) and the poll
   endpoint that re-renders one importing row (GET /queue/:id/row). Lean glue —
   the work lives in reader.ingest.

   Datastar requests get SSE patches (prepend the placeholder row, morph the
   settled row in); plain requests fall back to redirects/fragments, so the
   flow survives without JavaScript."
  (:require [integrant.core :as ig]
            [reader.ingest :as ingest]
            [reader.papers :as papers]
            [reader.domain.reading :as reading]
            [reader.ui.pages.home :as home]
            [reader.web.datastar :as datastar]
            [reader.web.request :as request]
            [reader.web.response :as response]))

(defn- importing
  "Common response for a started ingest: Datastar prepends the placeholder row
   into the list and clears the add form's bound $url signal; a plain POST falls
   back to a full reload (the row appears on the re-render)."
  [req queue-item label]
  (if (datastar/request? req)
    (datastar/sse req
                  (fn [gen]
                    (datastar/prepend! gen "#readables-list"
                                       (home/importing-row (:queue-items/id queue-item) label))
                    (datastar/signals! gen {:url ""})))
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

(defn- row-fragment
  "The current rendering of one queue row, by ingest status."
  [ds item]
  (case (ingest-status ds item)
    :done        (home/item item)
    :not-indexed (home/unavailable-row (:queue-item-id item) (:title item))
    :failed      (home/failed-row (:queue-item-id item) (:title item))
    (home/importing-row (:queue-item-id item) (:title item))))

(defmethod ig/init-key :reader.handlers.readables/row [_ {:keys [datasource]}]
  (fn [req]
    (let [id   (request/path-uuid req)
          item (and id (reading/queue-item datasource (:user-id req) id))
          item (when (and item (not= "archived" (:state item))) item)]
      (cond
        ;; The item is gone (archived, removed, or never visible to this user):
        ;; remove the stale row, which also stops its interval poll. The plain
        ;; fallback answers an empty 200 fragment for the same reason a 404
        ;; would be wrong — not-found renders whole-page chrome into an <li>.
        (nil? item)
        (if (datastar/request? req)
          (datastar/sse req (fn [gen] (datastar/remove! gen (str "#q-" id))))
          (response/fragment nil))

        (datastar/request? req)
        (datastar/patch req (row-fragment datasource item))

        :else
        (response/fragment (row-fragment datasource item))))))
