(ns reader.handlers.home
  (:require [integrant.core :as ig]
            [reader.reading :as reading]
            [reader.ui.pages.home :as home]
            [reader.web.response :as response]))

(defmethod ig/init-key :reader.handlers/home [_ {:keys [datasource]}]
  (fn [req]
    (response/html (home/render (reading/queue datasource (:user-id req))))))
