(ns reader.handlers.home
  (:require [integrant.core :as ig]
            [reader.ui.pages.home :as home]))

(defmethod ig/init-key :reader.handlers/home [_ _]
  (fn [_req]
    {:status  200
     :headers {"content-type" "text/html; charset=utf-8"}
     :body    (home/render)}))
