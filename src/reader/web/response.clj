(ns reader.web.response
  "Small ring-response helpers shared across handlers, so the handlers stay
   glue: read input, call the domain, pick a response."
  (:require [hiccup2.core :as h]
            [reader.ui.layout :as layout]))

(defn html
  "An HTML response. Status defaults to 200."
  ([body] (html 200 body))
  ([status body]
   {:status  status
    :headers {"content-type"           "text/html; charset=utf-8"
              "referrer-policy"        "no-referrer"
              "x-content-type-options" "nosniff"
              "x-frame-options"        "DENY"}
    :body    body}))

(def ^:private reader-content-security-policy
  (str "default-src 'self'; "
       "base-uri 'none'; object-src 'none'; frame-src 'none'; frame-ancestors 'none'; "
       "script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; "
       "img-src 'self' https: http: data:; font-src 'self' data:; "
       "form-action 'self'"))

(defn reader-html
  "An HTML response for sanitized stored reading content. Its route-specific CSP
   permits remote newsletter images but no remote/inline executable content."
  [body]
  (assoc-in (html body) [:headers "content-security-policy"]
            reader-content-security-policy))

(defn fragment
  "An HTML response whose body is a rendered hiccup fragment (no page chrome) —
   for HTMX partial swaps."
  [hiccup]
  (html (str (h/html hiccup))))

(defn see-other
  "A 303 redirect (POST -> GET, the form post/redirect/get pattern)."
  [location]
  {:status 303 :headers {"location" location}})

(defn not-found
  "A 404 HTML page."
  [message]
  (html 404 (layout/not-found message)))

(defn forbidden
  "A 403 HTML page."
  [message]
  (html 403 (layout/forbidden message)))

(defn server-error
  "A 500 HTML page."
  []
  (html 500 (layout/server-error)))

(defn expire-cookie
  "Add a Set-Cookie header to `response` that immediately expires cookie `name`."
  [response name]
  (assoc-in response [:headers "set-cookie"]
            (str name "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax")))
