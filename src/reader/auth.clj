(ns reader.auth
  "Authentication: verifying the Hanko session JWT and mapping its claims to a
   local user. Verifying a token is the one IO edge here — clj-jwt fetches and
   caches Hanko's JWKS and handles key rotation. The claim→attrs mapping and
   the allowlist check are pure."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.github.sikt-no.clj-jwt :as clj-jwt]))

(defn verify-token
  "Verify a Hanko session JWT against the JWKS at `jwks-url`. Returns the claims
   map on success, or nil when the token is missing, malformed, expired, lacks an
   `exp` claim, or is signed by an unknown key. When `issuer` is given it must
   match the token's `iss` claim.

   The `exp` is required, not merely honoured-when-present: buddy only checks
   expiry if the claim exists, so a token without one would otherwise verify
   forever — a session must have a lifetime.

   Catches Throwable on purpose: the token is fully attacker-controlled (a raw
   cookie value), and clj-jwt signals a malformed token with an AssertionError
   rather than an Exception — both should read as \"not authenticated\", never a
   500."
  ([jwks-url token] (verify-token jwks-url token nil))
  ([jwks-url token issuer]
   (when (and jwks-url (not (str/blank? token)))
     (try
       (let [claims (clj-jwt/unsign jwks-url token (cond-> {} issuer (assoc :iss issuer)))]
         (when (:exp claims) claims))
       (catch Throwable t
         (log/debug t "rejected session token")
         nil)))))

(defn claims->user-attrs
  "The local `users` attrs implied by a verified Hanko claims map: the Hanko
   subject becomes `hanko-id`, and the email claim's `address` becomes `email`.
   Hanko's `email` claim is an object ({address, is_primary, is_verified}); we
   tolerate a bare-string email too, so a token-shape change can't silently map
   `email` to nil and lock out an otherwise-valid identity."
  [{:keys [sub email]}]
  {:hanko-id sub :email (if (map? email) (:address email) email)})

(defn invited?
  "Is `email` on the allowlist? `allowed-emails` is expected already lower-cased
   (the auth middleware normalizes it once at init); the incoming email is
   lower-cased here, matching the citext `users.email` column."
  [allowed-emails email]
  (boolean
   (and email
        (contains? allowed-emails (str/lower-case email)))))
