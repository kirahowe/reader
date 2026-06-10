(ns reader.web.signature
  "HMAC-SHA256 request signing for server-to-server webhooks (the inbound-email
   worker). The worker signs `timestamp + \".\" + body`; the app verifies it with
   a constant-time compare so a wrong signature leaks no timing signal. Symmetric
   shared secret — the same value lives as a Fly secret here and a Wrangler secret
   in the worker."
  (:require [clojure.string :as str])
  (:import (java.security MessageDigest)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(defn- ->hex [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn sign
  "Lowercase-hex HMAC-SHA256 of `message` under `secret` (both UTF-8 strings)."
  [secret message]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes (str secret) "UTF-8") "HmacSHA256"))
    (->hex (.doFinal mac (.getBytes (str message) "UTF-8")))))

(defn valid?
  "True iff `provided` is the correct signature of `message` under `secret`. A
   blank secret or signature is always false (fail closed — an unconfigured
   endpoint rejects). Constant-time via MessageDigest/isEqual."
  [secret message provided]
  (boolean
   (and (not (str/blank? (str secret)))
        (not (str/blank? (str provided)))
        (MessageDigest/isEqual (.getBytes (sign secret message) "UTF-8")
                               (.getBytes (str provided) "UTF-8")))))
