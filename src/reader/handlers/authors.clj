(ns reader.handlers.authors
  (:require [integrant.core :as ig]
            [reader.authors :as authors]
            [reader.ui.pages.authors :as pages]
            [reader.web.response :as response]))

(defmethod ig/init-key :reader.handlers.authors/index [_ {:keys [datasource]}]
  (fn [_req]
    (response/html (pages/index (authors/list-sorted datasource)))))

(defmethod ig/init-key :reader.handlers.authors/show [_ {:keys [datasource]}]
  (fn [req]
    (let [slug   (get-in req [:path-params :slug])
          author (authors/by-slug datasource slug)]
      (if author
        (response/html (pages/show author (authors/affiliations-of datasource (:authors/id author))))
        (response/not-found "No author with that name.")))))
