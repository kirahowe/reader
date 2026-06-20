(ns reader.handlers.affiliations
  (:require [integrant.core :as ig]
            [reader.domain.affiliations :as affiliations]
            [reader.ui.pages.affiliations :as pages]
            [reader.web.response :as response]))

(defmethod ig/init-key :reader.handlers.affiliations/index [_ {:keys [datasource]}]
  (fn [_req]
    (response/html (pages/index (affiliations/list-sorted datasource)))))
