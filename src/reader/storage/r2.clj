(ns reader.storage.r2
  "Cloudflare R2 backend for the storage seam. R2 is S3-compatible, so this is a
   minimal AWS SigV4-signed S3 client over java.net.http — get/put one object by
   key, path-style, against the account's R2 endpoint. No SDK dependency; the
   signer (the only fiddly part) is unit-tested against AWS's published example
   vector. Loaded on demand by `reader.storage/open` only when `:backend :r2`."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [reader.storage :as storage])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.security MessageDigest)
           (java.time ZoneOffset ZonedDateTime)
           (java.time.format DateTimeFormatter)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def ^:private amz-fmt (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'"))
(def ^:private date-fmt (DateTimeFormatter/ofPattern "yyyyMMdd"))

(defn- ->hex ^String [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn- sha256-hex ^String [^bytes b]
  (->hex (.digest (MessageDigest/getInstance "SHA-256") b)))

(defn- hmac ^bytes [^bytes k ^bytes data]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. k "HmacSHA256"))
    (.doFinal mac data)))

(defn- utf8 ^bytes [^String s] (.getBytes s "UTF-8"))

(defn- signing-key ^bytes [secret datestamp region service]
  (-> (utf8 (str "AWS4" secret))
      (hmac (utf8 datestamp))
      (hmac (utf8 region))
      (hmac (utf8 service))
      (hmac (utf8 "aws4_request"))))

(defn- uri-encode
  "RFC3986 percent-encoding. Keeps the unreserved set; preserves '/' only when
   `keep-slash?` (for the path), since object keys carry real path separators."
  [^String s keep-slash?]
  (let [unreserved? (fn [i] (or (<= (int \A) i (int \Z))
                                (<= (int \a) i (int \z))
                                (<= (int \0) i (int \9))
                                (#{(int \-) (int \_) (int \.) (int \~)} i)))]
    (apply str
           (for [b (utf8 s)]
             (let [i (bit-and b 0xff)]
               (cond
                 (unreserved? i)                     (str (char i))
                 (and keep-slash? (= i (int \/)))    "/"
                 :else                               (format "%%%02X" i)))))))

(defn sigv4-signature
  "Compute an AWS SigV4 signature for a request. Pure — every input is supplied,
   so it's verifiable against AWS's published example vector. Returns
   {:signature <hex> :signed-headers <\";\"-joined>}. `headers` is a map of
   already-lowercased name -> value; `payload-hash` is the hex SHA-256 of the
   body (the empty-string hash for a bodyless GET)."
  [{:keys [method canonical-uri query headers payload-hash region service
           secret amzdate datestamp]}]
  (let [sorted         (sort-by key headers)
        canon-headers  (apply str (map (fn [[k v]] (str k ":" (str/trim (str v)) "\n")) sorted))
        signed-headers (str/join ";" (map key sorted))
        canon-req      (str method "\n" canonical-uri "\n" (or query "") "\n"
                            canon-headers "\n" signed-headers "\n" payload-hash)
        scope          (str datestamp "/" region "/" service "/aws4_request")
        string-to-sign (str "AWS4-HMAC-SHA256\n" amzdate "\n" scope "\n"
                            (sha256-hex (utf8 canon-req)))
        signature      (->hex (hmac (signing-key secret datestamp region service)
                                    (utf8 string-to-sign)))]
    {:signature signature :signed-headers signed-headers}))

(defn- signed-request
  "Build a signed java.net.http request for `method` on `key`. `body` is a byte
   array (PUT) or nil (GET)."
  [{:keys [account-id bucket access-key secret region]} method ^String key body content-type]
  (let [host         (str account-id ".r2.cloudflarestorage.com")
        path         (str "/" bucket "/" (uri-encode key true))
        payload      (or body (byte-array 0))
        payload-hash (sha256-hex payload)
        now          (ZonedDateTime/now ZoneOffset/UTC)
        amzdate      (.format now amz-fmt)
        datestamp    (.format now date-fmt)
        ;; host, x-amz-date and x-amz-content-sha256 are the signed headers;
        ;; content-type rides along unsigned (S3 permits unsigned headers).
        headers      {"host"                 host
                      "x-amz-content-sha256" payload-hash
                      "x-amz-date"           amzdate}
        {:keys [signature signed-headers]}
        (sigv4-signature {:method method :canonical-uri path :query "" :headers headers
                          :payload-hash payload-hash :region region :service "s3"
                          :secret secret :amzdate amzdate :datestamp datestamp})
        auth         (str "AWS4-HMAC-SHA256 Credential=" access-key "/" datestamp "/"
                          region "/s3/aws4_request, SignedHeaders=" signed-headers
                          ", Signature=" signature)
        publisher    (if body
                       (HttpRequest$BodyPublishers/ofByteArray body)
                       (HttpRequest$BodyPublishers/noBody))
        builder      (-> (HttpRequest/newBuilder (URI. (str "https://" host path)))
                         ;; Host is set automatically by HttpClient from the URI;
                         ;; setting it here would throw (restricted header).
                         (.method method publisher)
                         (.header "Authorization" auth)
                         (.header "x-amz-content-sha256" payload-hash)
                         (.header "x-amz-date" amzdate))]
    (.build (cond-> builder content-type (.header "content-type" content-type)))))

(defrecord R2Store [cfg ^HttpClient client]
  storage/Blobs
  (get-object [_ key]
    (let [resp   (.send client (signed-request cfg "GET" key nil nil)
                        (HttpResponse$BodyHandlers/ofByteArray))
          status (.statusCode resp)]
      (cond
        (= 200 status) (.body resp)
        (= 404 status) nil
        :else (throw (ex-info "R2 get-object failed" {:status status :key key})))))
  (put-object [_ key bytes content-type]
    (let [resp   (.send client (signed-request cfg "PUT" key bytes content-type)
                        (HttpResponse$BodyHandlers/ofString))
          status (.statusCode resp)]
      (when-not (<= 200 status 299)
        (throw (ex-info "R2 put-object failed" {:status status :key key})))
      key)))

(defn ->store
  "An R2-backed Blobs store. `cfg` needs :account-id :bucket :access-key :secret;
   :region defaults to R2's \"auto\"."
  [{:keys [account-id bucket access-key secret region] :or {region "auto"} :as cfg}]
  (when (some str/blank? [account-id bucket access-key secret])
    (throw (ex-info "R2 storage misconfigured (need account-id, bucket, access-key, secret)"
                    {:have (keys (into {} (remove (comp str/blank? val) cfg)))})))
  (log/info "storage backend r2" {:account-id account-id :bucket bucket})
  (->R2Store (assoc cfg :region region) (HttpClient/newHttpClient)))
