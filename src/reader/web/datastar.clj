(ns reader.web.datastar
  "Datastar SSE glue for the reader's handlers. Every reactive write in the app
   answers a Datastar request with a short-lived SSE stream: open, send one or
   more patches, close. Handlers keep a plain form-post fallback (303 redirect),
   so the UI still works with JavaScript off — `request?` is the fork.

   The helpers wrap the Datastar SDK so handlers never touch it directly:
   `patch` covers the common one-fragment morph; `sse` hands the open stream to
   a callback for multi-patch responses (prepend + signal reset, remove + …)."
  (:require [charred.api :as json]
            [hiccup2.core :as h]
            [starfederation.datastar.clojure.adapter.http-kit :as hk]
            [starfederation.datastar.clojure.api :as d*]))

(defn request?
  "True when `req` came from Datastar (an `@get`/`@post` action), so the caller
   should answer with an SSE patch instead of a redirect or full page."
  [req]
  (d*/datastar-request? req))

(defn- html [fragment] (str (h/html fragment)))

(defn sse
  "A one-shot SSE response: open the stream, call `(f gen)` to send patches,
   close. `f` receives the open SSE generator and uses the `patch!` helpers
   below."
  [req f]
  (hk/->sse-response req
                     {hk/on-open (fn [gen]
                                   (f gen)
                                   (d*/close-sse! gen))}))

(defn patch!
  "Morph `fragment` (hiccup) into the page — matched by element id."
  [gen fragment]
  (d*/patch-elements! gen (html fragment)))

(defn prepend!
  "Insert `fragment` (hiccup) as the first child of the element at `selector`."
  [gen selector fragment]
  (d*/patch-elements! gen (html fragment)
                      {d*/selector   selector
                       d*/patch-mode d*/pm-prepend}))

(defn remove!
  "Remove the element(s) matching `selector` from the page."
  [gen selector]
  (d*/remove-element! gen selector))

(defn signals!
  "Merge `m` (a map) into the page's signals — e.g. {:url \"\"} to clear the
   add-URL input after a successful submit."
  [gen m]
  (d*/patch-signals! gen (json/write-json-str m)))

(defn patch
  "The common case: an SSE response that morphs each of `fragments` in by id
   and closes."
  [req & fragments]
  (sse req (fn [gen] (run! #(patch! gen %) fragments))))
