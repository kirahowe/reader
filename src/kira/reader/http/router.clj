(ns kira.reader.http.router
  (:require [integrant.core :as ig]
            [reitit.ring :as ring]
            [kira.reader.ui.layout :as layout]))

(defn- home [_req]
  {:status  200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body    (layout/page
             "Reader"
             [:main
              [:h1 "Reader"]
              [:p "A quiet place for things you want to read."]
              [:p.muted "v0.1 — hello, world."]])})

(defn- health [_req]
  {:status 200 :headers {"content-type" "text/plain"} :body "ok"})

(defn handler [_deps]
  (ring/ring-handler
   (ring/router
    [["/"        {:get home}]
     ["/health"  {:get health}]
     ["/static/*" (ring/create-resource-handler {:root "public"})]])
   (ring/create-default-handler)))

(defmethod ig/init-key :kira.reader.http/handler [_ deps]
  (handler deps))
