(ns reader.concerns.reitit
  (:require [integrant.core :as ig]
            [reitit.ring :as ring]))

(defmethod ig/init-key :reader.concerns.reitit/ring-handler
  [_ {:keys [router default-handler opts]}]
  (ring/ring-handler router default-handler (or opts {})))

(defmethod ig/init-key :reader.concerns.reitit/router [_ {:keys [data opts]}]
  (ring/router data (or opts {})))

(defmethod ig/init-key :reader.concerns.reitit/default-handler [_ _]
  (ring/routes
   (ring/redirect-trailing-slash-handler {:method :strip})
   (ring/create-default-handler)))
