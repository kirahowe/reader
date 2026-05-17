(ns reader.log
  (:require [com.brunobonacci.mulog :as mu]
            [integrant.core :as ig]))

(defmethod ig/init-key :reader.log/publisher [_ {:keys [pretty?] :as cfg}]
  (let [opts (cond-> (assoc cfg :type :console)
               pretty? (assoc :pretty? true))]
    (mu/start-publisher! opts)))

(defmethod ig/halt-key! :reader.log/publisher [_ stop]
  (when (fn? stop) (stop)))
