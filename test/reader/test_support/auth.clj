(ns reader.test-support.auth
  "Test helpers for authenticated requests. Mints a `hanko` session cookie with
   clj-jwt/sign against the committed test JWKS — the same key the running
   system's :reader.auth/middleware verifies against (test.edn points its
   jwks-url at that resource)."
  (:require [clojure.java.io :as io]
            [com.github.sikt-no.clj-jwt :as clj-jwt]
            [ring.mock.request :as mock]))

(def ^:private jwks (io/resource "test-jwks.json"))

(def invited-email
  "Matches :reader.auth/middleware :allowed-emails in env/test/resources/test.edn."
  "allowed@x.test")

(defn- now-secs [] (quot (System/currentTimeMillis) 1000))

(defn- claims
  "Claims shaped like a real Hanko session JWT: `email` is an object, not a bare
   string (see reader.auth/claims->user-attrs)."
  [email exp]
  {:sub   (str "sub-" email)
   :email {:address email :is_primary true :is_verified true}
   :exp   exp})

(defn token
  "A signed session JWT for `email` (default the invited test user)."
  ([] (token invited-email))
  ([email] (clj-jwt/sign jwks "test-key" (claims email (+ (now-secs) 300)))))

(defn expired-token [email]
  (clj-jwt/sign jwks "test-key" (claims email (- (now-secs) 10))))

(defn authed
  "Attach a session cookie to `req` — the invited test user by default, or a
   given token string."
  ([req] (authed req (token)))
  ([req tok] (mock/header req "cookie" (str "hanko=" tok))))
