(ns reader.handlers.affiliations
  (:require [integrant.core :as ig]
            [reader.domain.affiliations :as affiliations]
            [reader.domain.readables :as readables]
            [reader.ui.pages.affiliations :as pages]
            [reader.web.response :as response]))

(defmethod ig/init-key :reader.handlers.affiliations/index [_ {:keys [datasource]}]
  (fn [_req]
    (response/html (pages/index (affiliations/list-sorted datasource)))))

(defmethod ig/init-key :reader.handlers.affiliations/show [_ {:keys [datasource]}]
  (fn [req]
    (let [slug (get-in req [:path-params :slug])
          aff  (affiliations/by-slug datasource slug)]
      (if aff
        (let [works (readables/by-source datasource (:affiliations/id aff))]
          (response/html (pages/show aff works (readables/contributors-of works))))
        (response/not-found "No source with that name.")))))
