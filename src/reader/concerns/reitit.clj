(ns reader.concerns.reitit
  (:require [integrant.core :as ig]
            [reitit.ring :as ring]
            [reitit.ring.middleware.parameters :as parameters]))

(defmethod ig/init-key :reader.concerns.reitit/ring-handler
  [_ {:keys [router default-handler opts]}]
  (ring/ring-handler router default-handler (or opts {})))

(defmethod ig/init-key :reader.concerns.reitit/router [_ {:keys [data opts]}]
  ;; Parse query/form params into :params for every route, so form POSTs land
  ;; as data the handlers can read. Kept in code, not the EDN routes, since a
  ;; middleware is a function and not an EDN literal.
  (ring/router data (-> (or opts {})
                        (update-in [:data :middleware]
                                   (fnil conj []) parameters/parameters-middleware))))

(defmethod ig/init-key :reader.concerns.reitit/default-handler [_ _]
  (ring/routes
   (ring/redirect-trailing-slash-handler {:method :strip})
   (ring/create-default-handler)))
