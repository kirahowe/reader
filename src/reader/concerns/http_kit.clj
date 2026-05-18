(ns reader.concerns.http-kit
  (:require [com.brunobonacci.mulog :as mu]
            [integrant.core :as ig]
            [org.httpkit.server :as http]))

(defmethod ig/init-key :reader.concerns/http-kit [_ {:keys [handler opts]}]
  (mu/log ::starting :opts opts)
  (let [server (http/run-server handler (assoc opts :legacy-return-value? false))]
    (mu/log ::started :port (http/server-port server))
    server))

(defmethod ig/halt-key! :reader.concerns/http-kit [_ server]
  (mu/log ::stopping)
  (http/server-stop! server))
