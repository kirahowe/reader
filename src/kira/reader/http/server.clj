(ns kira.reader.http.server
  (:require [com.brunobonacci.mulog :as mu]
            [integrant.core :as ig]
            [org.httpkit.server :as http]))

(defmethod ig/init-key :kira.reader.http/server [_ {:keys [handler port host]}]
  (mu/log ::starting :port port :host host)
  (http/run-server handler {:port port :ip host :legacy-return-value? false}))

(defmethod ig/halt-key! :kira.reader.http/server [_ server]
  (mu/log ::stopping)
  (http/server-stop! server))
