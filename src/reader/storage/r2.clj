(ns reader.storage.r2
  "Cloudflare R2 backend for the storage abstraction, via Cognitect aws-api (R2 is
   S3-compatible). One S3 client per store, pointed at the account's R2 endpoint;
   get/put a single object by key. Loaded on demand by `reader.storage/open` only
   when `:backend :r2`, so dev/test never pull the AWS deps at runtime."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [reader.storage :as storage])
  (:import (java.io InputStream)))

;; aws-api resolves the region's S3 endpoint *before* applying :endpoint-override,
;; so the region must be one it knows. R2's documented "auto" isn't, and yields
;; "No known endpoint." us-east-1 resolves, the override then points the request
;; at R2, and R2 accepts SigV4 signed for us-east-1.
(defn- s3-client [{:keys [account-id access-key secret region] :or {region "us-east-1"}}]
  (aws/client {:api                   :s3
               :region                region
               :endpoint-override     {:protocol :https
                                       :hostname (str account-id ".r2.cloudflarestorage.com")}
               :credentials-provider  (credentials/basic-credentials-provider
                                       {:access-key-id     access-key
                                        :secret-access-key secret})}))

(defn- not-found? [resp]
  (= :cognitect.anomalies/not-found (:cognitect.anomalies/category resp)))

(defn- anomaly? [resp]
  (some? (:cognitect.anomalies/category resp)))

(defrecord R2Store [client bucket]
  storage/Blobs
  (get-object [_ key]
    (let [resp (aws/invoke client {:op :GetObject :request {:Bucket bucket :Key key}})]
      (cond
        (not-found? resp) nil
        (anomaly? resp)   (throw (ex-info "R2 get-object failed" {:key key :response resp}))
        :else             (.readAllBytes ^InputStream (:Body resp)))))
  (put-object [_ key bytes content-type]
    (let [resp (aws/invoke client {:op      :PutObject
                                   :request {:Bucket bucket :Key key
                                             :Body bytes :ContentType content-type}})]
      (when (anomaly? resp)
        (throw (ex-info "R2 put-object failed" {:key key :response resp})))
      key))
  (enabled? [_] true))

(defn ->store
  "An R2-backed Blobs store. `cfg` needs :account-id :bucket :access-key :secret;
   :region defaults to us-east-1 (see s3-client). When credentials are absent it returns a
   disabled store (boots, throws on use) rather than failing startup — R2 is an
   optional feature (inbound email), so a half-configured prod still serves
   everything else; inbound email activates once the secrets are set."
  [{:keys [account-id bucket access-key secret] :as cfg}]
  (if (some str/blank? [account-id bucket access-key secret])
    (do (log/warn "R2 not configured (missing credentials); blob storage disabled"
                  {:missing (->> {:account-id account-id :bucket bucket
                                  :access-key access-key :secret secret}
                                 (filter (comp str/blank? val))
                                 (mapv key))})
        (storage/disabled-store :r2-unconfigured))
    (do (log/info "storage backend r2" {:account-id account-id :bucket bucket})
        (->R2Store (s3-client cfg) bucket))))
