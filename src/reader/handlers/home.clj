(ns reader.handlers.home
  (:require [integrant.core :as ig]
            [reader.domain.reading :as reading]
            [reader.domain.tags :as tags]
            [reader.ui.pages.home :as home]
            [reader.web.response :as response]))

(defmethod ig/init-key :reader.handlers/home [_ {:keys [datasource]}]
  (fn [req]
    (let [items  (tags/attach-effective datasource (reading/queue datasource (:user-id req)))
          active (get-in req [:query-params "tag"])]
      (response/html (home/render (tags/with-tag items active)
                                  active
                                  (tags/distinct-tags items))))))
