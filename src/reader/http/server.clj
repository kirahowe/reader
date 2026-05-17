(ns reader.http.server
  (:require [com.brunobonacci.mulog :as mu]
            [integrant.core :as ig]
            [org.httpkit.server :as http]))

(defmethod ig/init-key :reader.http/server [_ {:keys [handler port host]}]
  (mu/log ::starting :port port :host host)
  (let [server (http/run-server handler
                                {:port                 port
                                 :ip                   host
                                 :legacy-return-value? false})]
    (mu/log ::started :port (http/server-port server))
    server))

(defmethod ig/halt-key! :reader.http/server [_ server]
  (mu/log ::stopping)
  (http/server-stop! server))
