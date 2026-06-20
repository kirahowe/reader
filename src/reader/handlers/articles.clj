(ns reader.handlers.articles
  (:require [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [reader.domain.affiliations :as affiliations]
            [reader.domain.articles :as articles]
            [reader.domain.reading :as reading]
            [reader.ui.pages.articles :as pages]
            [reader.web.response :as response]))

(defmethod ig/init-key :reader.handlers.articles/new [_ {:keys [datasource]}]
  (fn [_req]
    (response/html (pages/new-form (affiliations/list-sorted datasource)))))

(defmethod ig/init-key :reader.handlers.articles/create [_ {:keys [datasource]}]
  (fn [req]
    ;; Create and enqueue share one transaction: a failed enqueue rolls the
    ;; article insert back, so we never persist an article that isn't queued.
    (let [{:keys [article errors]}
          (jdbc/with-transaction [tx datasource]
            (let [{:keys [article] :as result} (articles/create! tx (:params req))]
              (when article
                (reading/enqueue! tx (:user-id req) "article"
                                  (:articles/id article) {:source "manual"}))
              result))]
      (if article
        (response/see-other "/")
        (response/html (pages/new-form (affiliations/list-sorted datasource)
                                       (:params req)
                                       errors))))))
