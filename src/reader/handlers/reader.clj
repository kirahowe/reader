(ns reader.handlers.reader
  (:require [integrant.core :as ig]
            [reader.extract :as extract]
            [reader.domain.reading :as reading]
            [reader.domain.tags :as tags]
            [reader.ui.pages.reader :as pages]
            [reader.web.request :as request]
            [reader.web.response :as response]))

(defmethod ig/init-key :reader.handlers.reader/show [_ {:keys [datasource]}]
  (fn [req]
    ;; `open` is owner-scoped, so a forged or missing id (and a non-uuid, which
    ;; parses to nil) all answer 404 — never another user's content.
    (let [id (request/path-uuid req)]
      (if-let [{:keys [queue-item readable]} (and id (reading/open datasource (:user-id req) id))]
        (response/html (pages/show queue-item (extract/extract readable)
                                   (tags/effective-for-queue-item datasource queue-item)))
        (response/not-found "No such item in your reading list.")))))
