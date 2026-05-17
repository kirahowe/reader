(ns reader.http.router
  (:require [integrant.core :as ig]
            [reitit.ring :as ring]
            [reader.ui.pages.home :as home]))

(defn- html-response [body]
  {:status  200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body    body})

(defn- home-handler [_req]
  (html-response (home/render)))

(defn- health-handler [_req]
  {:status  200
   :headers {"content-type" "text/plain"}
   :body    "ok"})

(defn- routes [_deps]
  [["/"         {:get home-handler}]
   ["/health"   {:get health-handler}]
   ["/static/*" (ring/create-resource-handler {:root "public"})]])

(defn handler [deps]
  (ring/ring-handler
   (ring/router (routes deps))
   (ring/create-default-handler)))

(defmethod ig/init-key :reader.http/handler [_ deps]
  (handler deps))
