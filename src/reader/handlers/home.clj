(ns reader.handlers.home
  (:require [integrant.core :as ig]
            [reader.readables :as readables]
            [reader.ui.pages.home :as home]
            [reader.web.response :as response]))

(defmethod ig/init-key :reader.handlers/home [_ {:keys [datasource]}]
  (fn [_req]
    (response/html (home/render (readables/reading-list datasource)))))
