(ns reader.handlers.authors
  (:require [integrant.core :as ig]
            [reader.domain.authors :as authors]
            [reader.domain.readables :as readables]
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
        (let [aid   (:authors/id author)
              works (readables/by-author datasource aid)]
          (response/html (pages/show author
                                     (authors/institutions-of datasource aid)
                                     (readables/sources-of works)
                                     works)))
        (response/not-found "No author with that name.")))))
