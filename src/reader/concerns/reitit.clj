(ns reader.concerns.reitit
  (:require [integrant.core :as ig]
            [reitit.ring :as ring]
            [reitit.ring.middleware.parameters :as parameters]))

(defmethod ig/init-key :reader.concerns.reitit/ring-handler
  [_ {:keys [router default-handler opts]}]
  (ring/ring-handler router default-handler (or opts {})))

(defmethod ig/init-key :reader.concerns.reitit/router [_ {:keys [data middleware opts]}]
  ;; Middleware are functions, not EDN literals, so they're injected here rather
  ;; than in the route data. parameters-middleware (parses query/form params into
  ;; :params) runs first; `middleware` carries the cross-cutting components — CSRF
  ;; then auth — supplied via #ig/ref from config. Any :data :middleware already
  ;; on `opts` is appended, not clobbered.
  (let [opts  (or opts {})
        stack (into [parameters/parameters-middleware]
                    (concat middleware (get-in opts [:data :middleware])))]
    (ring/router data (assoc-in opts [:data :middleware] stack))))

(defmethod ig/init-key :reader.concerns.reitit/default-handler [_ _]
  ;; The default-handler runs OUTSIDE the route :data :middleware (auth + CSRF),
  ;; which only wrap matched routes. That's fine: it just 404s an unknown path or
  ;; emits a trailing-slash redirect — no protected handler runs unauthenticated.
  ;; If a real terminal handler is ever added here, gate it explicitly.
  (ring/routes
   (ring/redirect-trailing-slash-handler {:method :strip})
   (ring/create-default-handler)))
