(ns reader.handlers.health
  (:require [integrant.core :as ig]))

(defmethod ig/init-key :reader.handlers/health [_ _]
  (fn [_req]
    {:status  200
     :headers {"content-type" "text/plain"}
     :body    "ok"}))
