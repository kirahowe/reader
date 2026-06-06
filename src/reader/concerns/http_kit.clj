(ns reader.concerns.http-kit
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [org.httpkit.server :as http]))

(defmethod ig/init-key :reader.concerns/http-kit [_ {:keys [handler opts]}]
  (log/info "http-kit starting" {:opts opts})
  (let [server (http/run-server handler (assoc opts :legacy-return-value? false))]
    (log/info "http-kit started" {:port (http/server-port server)})
    server))

(defmethod ig/halt-key! :reader.concerns/http-kit [_ server]
  (log/info "http-kit stopping")
  (http/server-stop! server))
