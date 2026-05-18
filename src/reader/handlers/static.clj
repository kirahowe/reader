(ns reader.handlers.static
  (:require [integrant.core :as ig]
            [reitit.ring :as ring]))

(defmethod ig/init-key :reader.handlers/static [_ {:keys [root]}]
  (ring/create-resource-handler {:root (or root "public")}))
