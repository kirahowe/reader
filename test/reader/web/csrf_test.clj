(ns reader.web.csrf-test
  "Unit tests for the Origin/Referer CSRF gate. `wrap-csrf` is exercised
   directly against a stub handler — no system needed."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.web.csrf :as csrf]
            [ring.mock.request :as mock]))

(def ^:private site-origin "https://reader.test")

(def ^:private handler
  (csrf/wrap-csrf (fn [_req] {:status 200 :body "ok"}) site-origin))

(deftest csrf-gate
  (testing "safe methods are never checked, even from a foreign origin"
    (doseq [method [:get :head :options]]
      (is (= 200 (:status (handler (-> (mock/request method "/")
                                       (mock/header "origin" "https://evil.test")))))
          (str (name method) " passes regardless of origin"))))

  (testing "an unsafe method with a matching Origin passes"
    (is (= 200 (:status (handler (-> (mock/request :post "/readables")
                                     (mock/header "origin" site-origin)))))))

  (testing "an unsafe method from a foreign Origin is rejected"
    (let [{:keys [status body]} (handler (-> (mock/request :post "/readables")
                                             (mock/header "origin" "https://evil.test")))]
      (is (= 403 status))
      (is (= "bad origin" body))))

  (testing "Referer is the fallback when Origin is absent"
    (is (= 200 (:status (handler (-> (mock/request :post "/readables")
                                     (mock/header "referer" (str site-origin "/readables"))))))
        "a same-origin Referer passes")
    (is (= 200 (:status (handler (-> (mock/request :post "/readables")
                                     (mock/header "referer" site-origin)))))
        "a bare-origin Referer (no path, e.g. Referrer-Policy: origin) still passes")
    (is (= 403 (:status (handler (-> (mock/request :post "/readables")
                                     (mock/header "referer" "https://evil.test/x")))))
        "a foreign Referer is rejected"))

  (testing "a look-alike host can't slip past the Referer origin check"
    (is (= 403 (:status (handler (-> (mock/request :post "/readables")
                                     (mock/header "referer" (str site-origin ".evil.test/x"))))))
        "comparing parsed origins stops reader.test matching reader.test.evil.test"))

  (testing "a request with neither header passes — deliberately not a browser write"
    (is (= 200 (:status (handler (mock/request :post "/readables"))))))

  (testing "a trailing slash on the configured origin is tolerated"
    (let [h (csrf/wrap-csrf (fn [_req] {:status 200 :body "ok"}) (str site-origin "/"))]
      (is (= 200 (:status (h (-> (mock/request :post "/readables")
                                 (mock/header "origin" site-origin)))))
          "config 'https://reader.test/' still matches Origin 'https://reader.test'")))

  (testing "the gate is inert when no site-origin is configured"
    (let [h (csrf/wrap-csrf (fn [_req] {:status 200 :body "ok"}) nil)]
      (is (= 200 (:status (h (-> (mock/request :post "/readables")
                                 (mock/header "origin" "https://evil.test")))))
          "an unconfigured gate passes everything rather than rejecting all writes"))))
