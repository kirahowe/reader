(ns reader.http-test
  "Integration tests for the HTTP layer.

   `routes-via-handler` stands up the real handler component and
   calls it with synthetic ring requests — exercises routing and
   every leaf handler without binding a socket.

   `end-to-end-server-binds-and-serves` brings up the full system
   on an ephemeral port and hits it with a real HTTP client."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [org.httpkit.server :as http-kit]
            [reader.sys :as sys])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(defn- prepped-config []
  (sys/prep-config (sys/load-configs ["base-system.edn" "env.edn"])))

(defn- with-system [keys f]
  (let [system (ig/init (prepped-config) keys)]
    (try (f system) (finally (ig/halt! system)))))

(defn- GET [port path]
  (let [client (HttpClient/newHttpClient)
        req    (-> (HttpRequest/newBuilder)
                   (.uri (URI/create (str "http://localhost:" port path)))
                   .GET
                   .build)]
    (.send client req (HttpResponse$BodyHandlers/ofString))))

(deftest routes-via-handler
  (with-system [:reader.http/handler]
    (fn [system]
      (let [handler (:reader.http/handler system)]

        (testing "GET / returns the home page"
          (let [{:keys [status headers body]} (handler {:request-method :get :uri "/"})]
            (is (= 200 status))
            (is (re-find #"(?i)text/html" (get headers "content-type")))
            (is (re-find #"<!DOCTYPE html>" body))
            (is (re-find #"<h1>Reader</h1>" body))))

        (testing "GET /health returns ok as text/plain"
          (let [{:keys [status headers body]} (handler {:request-method :get :uri "/health"})]
            (is (= 200 status))
            (is (re-find #"text/plain" (get headers "content-type")))
            (is (= "ok" body))))

        (testing "GET /static/css/tokens.css is served from resources/public"
          (let [{:keys [status body]} (handler {:request-method :get :uri "/static/css/tokens.css"})]
            (is (= 200 status))
            (is (re-find #":root" (slurp body)))))

        (testing "Unknown routes return 404"
          (let [{:keys [status]} (handler {:request-method :get :uri "/no-such-route"})]
            (is (= 404 status))))))))

(deftest end-to-end-server-binds-and-serves
  (with-system [:reader.http/server]
    (fn [system]
      (let [server (:reader.http/server system)
            port   (http-kit/server-port server)]
        (is (pos? port) "server bound to an ephemeral port")

        (testing "GET / over the wire"
          (let [resp (GET port "/")]
            (is (= 200 (.statusCode resp)))
            (is (re-find #"<h1>Reader</h1>" (.body resp)))))

        (testing "GET /health over the wire"
          (let [resp (GET port "/health")]
            (is (= 200 (.statusCode resp)))
            (is (= "ok" (.body resp)))))))))
