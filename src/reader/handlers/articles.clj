(ns reader.handlers.articles
  (:require [integrant.core :as ig]
            [reader.affiliations :as affiliations]
            [reader.articles :as articles]
            [reader.ui.pages.articles :as pages]
            [reader.web.response :as response]))

(defmethod ig/init-key :reader.handlers.articles/new [_ {:keys [datasource]}]
  (fn [_req]
    (response/html (pages/new-form (affiliations/list-sorted datasource)))))

(defmethod ig/init-key :reader.handlers.articles/create [_ {:keys [datasource]}]
  (fn [req]
    (let [{:keys [article errors]} (articles/create! datasource (:params req))]
      (if article
        (response/see-other "/")
        (response/html (pages/new-form (affiliations/list-sorted datasource)
                                       (:params req)
                                       errors))))))
