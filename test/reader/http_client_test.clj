(ns reader.http-client-test
  "Tests for reader.http — the outbound trusted-API client. Spins a local http-kit
   server on an ephemeral port and drives request! against it over a real socket.
   (Named *-client-test to avoid colliding with reader.http-test, which covers the
   inbound web stack.)"
  (:require [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as server]
            [reader.http :as http]))

(defn- with-server
  "Run `f` with a local server (ring `handler`), passing it the bound port, and
   stop the server afterward."
  [handler f]
  (let [srv (server/run-server handler {:port 0 :legacy-return-value? false})]
    (try (f (server/server-port srv))
         (finally (server/server-stop! srv)))))

(deftest request!-returns-status-and-body
  (testing "a 200 delivers status + text body, no error"
    (with-server (fn [_] {:status 200 :headers {"content-type" "text/plain"} :body "hello"})
      (fn [port]
        (let [{:keys [status body error]} @(http/request! (str "http://localhost:" port "/"))]
          (is (nil? error))
          (is (= 200 status))
          (is (= "hello" body)))))))

(deftest request!-surfaces-non-200
  (testing "a non-200 status comes back as-is (the caller decides what it means)"
    (with-server (fn [_] {:status 404 :body "nope"})
      (fn [port]
        (is (= 404 (:status @(http/request! (str "http://localhost:" port "/missing")))))))))

(deftest request!-sets-error-on-connection-failure
  (testing "an unreachable host resolves the promise with :error, not a throw"
    (let [{:keys [status error]} @(http/request! "http://127.0.0.1:1/")]
      (is (some? error))
      (is (nil? status)))))

(deftest request!-runs-concurrently
  (testing "several requests can be in flight at once and each derefs independently"
    (with-server (fn [_] (Thread/sleep 100) {:status 200 :body "ok"})
      (fn [port]
        (let [url (str "http://localhost:" port "/")
              a   (http/request! url)            ; both fired before either deref
              b   (http/request! url)]
          (is (= 200 (:status @a)))
          (is (= 200 (:status @b))))))))
