(ns reader.concerns.reitit
  (:require [integrant.core :as ig]
            [reitit.ring :as ring]))

(defmethod ig/init-key :reader.concerns.reitit/ring-handler
  [_ {:keys [router default-handler opts]}]
  (ring/ring-handler router default-handler (or opts {})))

(defmethod ig/init-key :reader.concerns.reitit/router [_ {:keys [data middleware opts]}]
  ;; `middleware` is the whole cross-cutting stack in outermost-first order,
  ;; assembled in the system config. Middleware are functions, not EDN literals,
  ;; so each is its own Integrant component injected via #ig/ref; mounting them
  ;; on the router :data applies them to every matched route.
  (ring/router data (assoc-in (or opts {}) [:data :middleware] (vec middleware))))

(defmethod ig/init-key :reader.concerns.reitit/default-handler [_ _]
  ;; The default-handler runs OUTSIDE the route :data :middleware (auth + CSRF),
  ;; which only wrap matched routes. That's fine: it just 404s an unknown path or
  ;; emits a trailing-slash redirect — no protected handler runs unauthenticated.
  ;; If a real terminal handler is ever added here, gate it explicitly.
  (ring/routes
   (ring/redirect-trailing-slash-handler {:method :strip})
   (ring/create-default-handler)))
