(ns reader.handlers.readables
  "Ingest UI handlers: paste a URL to import (POST /readables) and the poll
   endpoint that re-renders one importing row (GET /queue/:id/row). Lean glue —
   the work lives in reader.ingest."
  (:require [integrant.core :as ig]
            [reader.ingest :as ingest]
            [reader.reading :as reading]
            [reader.ui.pages.home :as home]
            [reader.web.request :as request]
            [reader.web.response :as response]))

(defmethod ig/init-key :reader.handlers.readables/create [_ {:keys [datasource]}]
  (fn [req]
    (if-let [url (ingest/normalize-url (get-in req [:params "url"]))]
      (let [{:keys [queue-item]} (ingest/start! datasource (:user-id req) url)]
        ;; HTMX swaps the new placeholder row in; a non-HTMX POST falls back to
        ;; a full reload (the row appears on the re-rendered list).
        (if (get-in req [:headers "hx-request"])
          (response/fragment (home/importing-row (:queue-items/id queue-item) url))
          (response/see-other "/")))
      (response/see-other "/"))))

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
         (case (ingest/status datasource (:id item))
           :done   (home/item item)
           :failed (home/failed-row id (:title item))
           (home/importing-row id (:title item))))))))
