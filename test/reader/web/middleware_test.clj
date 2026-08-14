(ns reader.web.middleware-test
  "Unit tests for the cross-cutting ring middleware. Each wrapper is exercised
   directly against a stub handler — no system needed."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.web.middleware :as mw]
            [ring.mock.request :as mock]))

(deftest request-log-passes-the-response-through
  (testing "the logging wrapper returns the downstream response unchanged"
    (let [resp    {:status 200 :headers {"x" "y"} :body "ok"}
          handler (mw/wrap-request-log (constantly resp))]
      (is (= resp (handler (mock/request :get "/")))))))

(deftest exception-catches-and-renders-500
  (testing "a normal response passes through untouched"
    (let [resp    {:status 200 :body "ok"}
          handler (mw/wrap-exception (constantly resp))]
      (is (= resp (handler (mock/request :get "/"))))))

  (testing "a thrown exception becomes a 500 HTML page, not a propagated throw"
    (let [handler (mw/wrap-exception
                   (fn [_] (throw (ex-info "boom" {}))))
          {:keys [status headers body]} (handler (mock/request :get "/"))]
      (is (= 500 status))
      (is (= "text/html; charset=utf-8" (get headers "content-type")))
      (is (= "no-referrer" (get headers "referrer-policy")))
      (is (= "nosniff" (get headers "x-content-type-options")))
      (is (string? body)))))
