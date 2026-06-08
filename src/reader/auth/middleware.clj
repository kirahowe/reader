(ns reader.auth.middleware
  "The authentication gate. A reitit middleware mounted on the whole router that
   lets `:public?` routes through and, for everything else, verifies the Hanko
   session cookie, provisions/loads the local user (invite-gated), and attaches
   `:user`/`:user-id` to the request. Unauthenticated requests redirect a browser
   GET/HEAD to `/login` and answer other methods with 401."
  (:require [clojure.string :as str]
            [integrant.core :as ig]
            [reader.auth :as auth]
            [reader.users :as users]
            [reader.web.response :as response]))

(defn- public? [req]
  (boolean (-> req :reitit.core/match :data :public?)))

(defn- session-token
  "The raw `hanko` session JWT from the Cookie header, or nil."
  [req]
  (some->> (get-in req [:headers "cookie"])
           (re-find #"(?:^|;\s*)hanko=([^;]+)")
           second))

(defn- resolve-user
  "The local user for verified identity `attrs`: an existing user, or a freshly
   provisioned one when the email is invited. nil means \"valid Hanko identity,
   but not on the invite list\"."
  [ds allowed-emails attrs]
  (or (users/find-by-identity! ds attrs)
      (when (auth/invited? allowed-emails (:email attrs))
        ;; Check-then-insert: two concurrent first requests from the same new
        ;; user can both miss find-by-identity! and race to create!. The loser
        ;; hits a unique constraint — recover by re-reading the row the winner
        ;; just wrote, so the race admits the user instead of 500ing. Any other
        ;; SQL failure is genuine and rethrows.
        (try
          (users/create! ds attrs)
          (catch java.sql.SQLException e
            (or (users/find-by-identity! ds attrs)
                (throw e)))))))

(defn- unauthenticated [req]
  ;; HEAD is a safe browser/crawler method that should mirror GET, so it gets the
  ;; same /login redirect; other methods (POST etc.) get a plain 401.
  (if (contains? #{:get :head} (:request-method req))
    (response/see-other "/login")
    {:status 401 :headers {"content-type" "text/plain"} :body "unauthenticated"}))

(defn- jwks-url-for
  "Hanko serves its JWK Set at the well-known path off the API base, so the JWKS
   URL is fully determined by `api-url` — real environments supply only the base."
  [api-url]
  (str (str/replace (str api-url) #"/+$" "") "/.well-known/jwks.json"))

(defn wrap-auth [handler {:keys [jwks-url issuer datasource allowed-emails]}]
  (fn [req]
    (if (public? req)
      (handler req)
      (if-let [claims (auth/verify-token jwks-url (session-token req) issuer)]
        (if-let [user (resolve-user datasource allowed-emails (auth/claims->user-attrs claims))]
          (handler (assoc req :user user :user-id (:users/id user)))
          (response/forbidden "You're signed in, but this address isn't on the invite list yet."))
        (unauthenticated req)))))

(defmethod ig/init-key :reader.auth/middleware
  [_ {:keys [jwks-url api-url issuer datasource allowed-emails]}]
  ;; Derive the JWKS URL from the Hanko API base; an explicit `jwks-url` still
  ;; wins so tests verify against a committed local JWKS (see test.edn).
  (let [jwks-url (or jwks-url (jwks-url-for api-url))
        allowed  (into #{} (map str/lower-case) allowed-emails)]
    {:name ::auth
     :wrap (fn [handler]
             (wrap-auth handler {:jwks-url       jwks-url
                                 :issuer         issuer
                                 :datasource     datasource
                                 :allowed-emails allowed}))}))
