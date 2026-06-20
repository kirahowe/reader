(ns reader.util.url
  "URL canonicalization for identity and dedup. Pure and stdlib-only
   (java.net.URI): lowercases the scheme and host, drops the default port,
   strips the fragment, and preserves the path and query — so trivially
   equivalent URLs (case, default port, #fragment) collapse to one key.
   Returns nil for anything that isn't a parseable http(s) URL, which doubles
   as the ingest boundary's validity check."
  (:require [clojure.string :as str])
  (:import [java.net URI]))

(defn- default-port? [scheme port]
  (or (and (= scheme "http") (= port 80))
      (and (= scheme "https") (= port 443))))

(defn canonicalize
  "Canonical http(s) URL string for `s`, or nil when it isn't a parseable
   http(s) URL."
  [s]
  (when-let [s (some-> s str/trim not-empty)]
    (try
      (let [u      (URI. s)
            scheme (some-> (.getScheme u) str/lower-case)
            host   (some-> (.getHost u) str/lower-case)
            port   (.getPort u)
            path   (.getRawPath u)
            query  (.getRawQuery u)]
        (when (and (#{"http" "https"} scheme) (not (str/blank? host)))
          (str scheme "://" host
               (when (and (not= -1 port) (not (default-port? scheme port))) (str ":" port))
               (if (str/blank? path) "/" path)
               (when query (str "?" query)))))
      (catch Exception _ nil))))

(defn valid?
  "True iff `s` is a parseable http(s) URL (i.e. it canonicalizes)."
  [s]
  (some? (canonicalize s)))
