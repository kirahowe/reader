(ns reader.handlers.inbound
  "POST /api/inbound — the inbound-email receiver, an Integrant abstraction (one key,
   `:reader.handlers/inbound`) whose `:impl` each environment chooses:

     :webhook  (prod)            verify the Cloudflare worker's HMAC-signed
                                 notification — the .eml is already in R2, the
                                 body is JSON {alias, r2-key, message-id, …}.
     :direct   (dev/test/PR)     accept a raw .eml body + `?alias=` directly,
                                 no HMAC/worker/R2 — store it and run the SAME
                                 :ingest-email downstream. Optionally token-gated.

   Lean glue over reader.web.signature + reader.inbound."
  (:require [charred.api :as json]
            [clojure.string :as str]
            [integrant.core :as ig]
            [reader.inbound :as inbound]
            [reader.web.signature :as signature])
  (:import (java.io InputStream)))

(def ^:private parse-json (json/parse-json-fn {:key-fn keyword}))

(defn- text [status body]
  {:status status :headers {"content-type" "text/plain"} :body body})

(defn- parse-ts [s]
  (try (Long/parseLong (str/trim (str s))) (catch Exception _ nil)))

;; ── :webhook (prod) — HMAC-verified notification ──────────────────────────

(defn handle
  "The webhook request flow, with `now-secs` (a 0-arg fn) and `secret` injected
   so tests can drive freshness and signing deterministically."
  [ds secret now-secs req]
  (let [raw    (some-> (:body req) slurp)
        ts-str (get-in req [:headers "x-reader-timestamp"])
        ts     (parse-ts ts-str)
        sig    (get-in req [:headers "x-reader-signature"])]
    (cond
      (str/blank? raw)
      (text 400 "empty body")

      (not (signature/valid? secret (str ts-str "." raw) sig))
      (text 401 "bad signature")

      (not (inbound/fresh? (now-secs) ts))
      (text 401 "stale timestamp")

      :else
      (let [payload (try (parse-json raw) (catch Exception _ ::bad))]
        (cond
          (= ::bad payload)                      (text 400 "malformed json")
          (not (inbound/valid-payload? payload)) (text 400 "invalid payload")
          :else (if (inbound/accept! ds payload)
                  (text 202 "accepted")
                  (text 404 "unknown alias")))))))

;; ── :direct (dev/test/PR) — raw .eml, no HMAC ─────────────────────────────

(defn direct-handle
  "The direct request flow: a raw `.eml` body and an `alias` query param, stored
   and ingested through the same pipeline. Gated by `token` when one is set (the
   `x-reader-token` header must match) — leave `token` nil/blank for localhost."
  [ds store token req]
  (let [supplied (get-in req [:headers "x-reader-token"])
        alias    (get-in req [:query-params "alias"])
        raw      (some-> ^InputStream (:body req) .readAllBytes)]
    (cond
      (and (not (str/blank? token)) (not= token supplied)) (text 401 "bad token")
      (str/blank? alias)                                   (text 400 "missing alias query param")
      (or (nil? raw) (zero? (alength ^bytes raw)))         (text 400 "empty body")
      (inbound/deliver! ds store alias raw)                (text 202 "accepted")
      :else                                                (text 404 "unknown alias"))))

(defmethod ig/init-key :reader.handlers/inbound
  [_ {:keys [impl datasource hmac-secret storage token]}]
  (case impl
    :webhook (fn [req]
               (handle datasource hmac-secret #(quot (System/currentTimeMillis) 1000) req))
    :direct  (fn [req]
               (direct-handle datasource storage token req))
    (throw (ex-info "unknown inbound handler :impl (want :webhook or :direct)" {:impl impl}))))
