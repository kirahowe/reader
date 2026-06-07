(ns reader.auth-test
  "Unit tests for token verification and the pure claim/allowlist helpers.
   Tokens are minted with `clj-jwt/sign` against the committed test JWKS
   (`env/test/resources/test-jwks.json`), whose private key signs and whose
   public key `verify-token` checks — the real verification path, no Hanko."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.github.sikt-no.clj-jwt :as clj-jwt]
            [reader.auth :as auth]))

(def ^:private jwks (io/resource "test-jwks.json"))

(defn- now-secs [] (quot (System/currentTimeMillis) 1000))

(defn- mint [claims]
  (clj-jwt/sign jwks "test-key" (merge {:exp (+ (now-secs) 60)} claims)))

(deftest verify-token-test
  (testing "a valid token returns its claims"
    (let [claims (auth/verify-token jwks (mint {:sub "h-1" :email "a@b.test"}))]
      (is (= "h-1" (:sub claims)))
      (is (= "a@b.test" (:email claims)))))

  (testing "an expired token is rejected"
    (is (nil? (auth/verify-token jwks (mint {:sub "h-1" :exp (- (now-secs) 10)})))))

  (testing "a token with no exp claim is rejected (a session must have a lifetime)"
    (is (nil? (auth/verify-token jwks (clj-jwt/sign jwks "test-key" {:sub "h-1"})))))

  (testing "a tampered token is rejected"
    (is (nil? (auth/verify-token jwks (str (mint {:sub "h-1"}) "x")))))

  (testing "a structurally invalid token is rejected, not thrown"
    (is (nil? (auth/verify-token jwks "not-a-jwt"))))

  (testing "a blank or nil token is rejected"
    (is (nil? (auth/verify-token jwks "")))
    (is (nil? (auth/verify-token jwks nil))))

  (testing "issuer is enforced when supplied"
    (let [token (mint {:sub "h-1" :iss "https://reader.hanko.io"})]
      (is (nil? (auth/verify-token jwks token "https://evil.example")))
      (is (= "h-1" (:sub (auth/verify-token jwks token "https://reader.hanko.io")))))))

(deftest claims->user-attrs-test
  (testing "maps the Hanko subject and the email object's address onto user attrs"
    (is (= {:hanko-id "h-9" :email "x@y.test"}
           (auth/claims->user-attrs
            {:sub "h-9" :email {:address "x@y.test" :is_primary true :is_verified true}}))))

  (testing "tolerates a bare-string email claim instead of an object"
    (is (= {:hanko-id "h-9" :email "x@y.test"}
           (auth/claims->user-attrs {:sub "h-9" :email "x@y.test"})))))

(deftest invited?-test
  ;; The allowlist is passed already lower-cased, as the auth middleware init
  ;; produces it; invited? lower-cases only the incoming email.
  (let [allowed #{"a@b.test" "c@d.test"}]
    (testing "matches case-insensitively on the incoming email"
      (is (auth/invited? allowed "a@b.test"))
      (is (auth/invited? allowed "A@B.test"))
      (is (auth/invited? allowed "C@D.test")))
    (testing "rejects unknown emails and nil"
      (is (not (auth/invited? allowed "z@z.test")))
      (is (not (auth/invited? allowed nil))))))
