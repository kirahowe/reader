(ns kira.reader.log
  (:require [com.brunobonacci.mulog :as mu]
            [integrant.core :as ig]))

(defmethod ig/init-key ::publisher [_ {:keys [pretty?] :as cfg}]
  (let [opts (cond-> (assoc cfg :type :console)
               pretty? (assoc :pretty? true))]
    (mu/start-publisher! opts)))

(defmethod ig/halt-key! ::publisher [_ stop]
  (when (fn? stop) (stop)))
