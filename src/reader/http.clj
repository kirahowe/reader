(ns reader.http
  "Outbound HTTP for trusted, fixed-host APIs (OpenAlex, arXiv) via http-kit's
   async client. `request!` issues a GET without ever blocking the caller's thread
   to do so and returns a promise, so several requests can be in flight at once —
   fire them, then deref. A request timeout keeps a hung peer from wedging the
   worker thread that derefs.

   NOT for user-supplied URLs: there is no SSRF guard here because every caller
   targets a hardcoded host. Anything fetched from a pasted/redirecting URL must
   go through reader.ingest.fetch's SSRF-pinned client instead."
  (:require [org.httpkit.client :as http]))

(def ^:private timeout-ms 10000)
(def ^:private user-agent "Reader/1.0 (+https://themiscellany.app)")

(defn request!
  "Async GET `url`. Returns a promise delivering {:status :body :error}: the HTTP
   status and text body of a completed response, or :error set (status/body nil)
   on timeout or connection failure. Deref to await; fire several `request!`s
   before derefing any to overlap them on http-kit's event loop."
  [url]
  (http/request {:url     url
                 :method  :get
                 :as      :text
                 :timeout timeout-ms
                 :headers {"User-Agent" user-agent}}
                (fn [{:keys [status body error]}]
                  {:status status :body body :error error})))
