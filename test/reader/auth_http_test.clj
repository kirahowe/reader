(ns reader.auth-http-test
  "Integration tests for the auth gate. The full system is brought up with the
   auth middleware wired and its jwks-url pointed at the test JWKS; requests are
   driven through the real ring handler with minted `hanko` cookies."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.db.crud :as crud]
            [reader.test-support.auth :as test-auth]
            [reader.test-support.setup :refer [with-system]]
            [ring.mock.request :as mock]))

(deftest auth-gate
  (with-system [system]
    (let [ds      (:reader.db/datasource system)
          handler (:reader.concerns.reitit/ring-handler system)]

      (testing "public routes need no session"
        (is (= 200 (:status (-> (mock/request :get "/health") handler))))
        (let [{:keys [status body]} (-> (mock/request :get "/login") handler)]
          (is (= 200 status))
          (is (re-find #"hanko-auth" body) "the login page mounts the Hanko element")))

      (testing "a protected GET without a session redirects to /login"
        (let [{:keys [status headers]} (-> (mock/request :get "/") handler)]
          (is (= 303 status))
          (is (= "/login" (get headers "location")))))

      (testing "a protected non-GET without a session is 401, not a redirect"
        (is (= 401 (:status (-> (mock/request :post "/readables") handler)))))

      (testing "an invited user is admitted and provisioned on first request"
        (let [{:keys [status]} (-> (mock/request :get "/") test-auth/authed handler)]
          (is (= 200 status))
          (is (some? (crud/find-1 ds :users {:email test-auth/invited-email}))
              "a user row is created from the verified identity")))

      (testing "a valid session for a non-invited address is forbidden, writes nothing"
        (let [{:keys [status]} (-> (mock/request :get "/")
                                   (test-auth/authed (test-auth/token "outsider@x.test"))
                                   handler)]
          (is (= 403 status))
          (is (nil? (crud/find-1 ds :users {:email "outsider@x.test"})))))

      (testing "an expired session is rejected like a missing one"
        (let [{:keys [status headers]} (-> (mock/request :get "/")
                                           (test-auth/authed (test-auth/expired-token test-auth/invited-email))
                                           handler)]
          (is (= 303 status))
          (is (= "/login" (get headers "location")))))

      (testing "logout clears the session cookie and redirects to /login"
        (let [{:keys [status headers]} (-> (mock/request :post "/logout") handler)
              set-cookie (get headers "set-cookie")]
          (is (= 303 status))
          (is (= "/login" (get headers "location")))
          (is (re-find #"(?i)hanko=;" set-cookie) "the cookie is expired")
          ;; The clearing cookie must match Hanko's host-only Path=/ cookie or the
          ;; browser keeps the session; assert the attributes that make it match.
          (is (re-find #"(?i)Max-Age=0" set-cookie) "it expires immediately")
          (is (re-find #"(?i)Path=/" set-cookie) "it is scoped to the whole site"))))))
