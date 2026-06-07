(ns reader.web.response
  "Small ring-response helpers shared across handlers, so the handlers stay
   glue: read input, call the domain, pick a response."
  (:require [reader.ui.layout :as layout]))

(defn html
  "An HTML response. Status defaults to 200."
  ([body] (html 200 body))
  ([status body]
   {:status  status
    :headers {"content-type" "text/html; charset=utf-8"}
    :body    body}))

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

(defn expire-cookie
  "Add a Set-Cookie header to `response` that immediately expires cookie `name`."
  [response name]
  (assoc-in response [:headers "set-cookie"]
            (str name "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax")))
