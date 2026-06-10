(ns reader.handlers.inbound
  "POST /api/inbound — the Cloudflare email worker's webhook. Public (no session
   cookie) but HMAC-signed: read the raw body, verify the signature over
   `timestamp + \".\" + body`, check the timestamp is fresh, validate the payload,
   then route it. Lean glue over reader.web.signature + reader.inbound."
  (:require [charred.api :as json]
            [clojure.string :as str]
            [integrant.core :as ig]
            [reader.inbound :as inbound]
            [reader.web.signature :as signature]))

(def ^:private parse-json (json/parse-json-fn {:key-fn keyword}))

(defn- text [status body]
  {:status status :headers {"content-type" "text/plain"} :body body})

(defn- parse-ts [s]
  (try (Long/parseLong (str/trim (str s))) (catch Exception _ nil)))

(defn handle
  "The pure-ish request flow, with `now-secs` (a 0-arg fn) and `secret` injected
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

(defmethod ig/init-key :reader.handlers.inbound/create [_ {:keys [datasource hmac-secret]}]
  (fn [req]
    (handle datasource hmac-secret #(quot (System/currentTimeMillis) 1000) req)))
