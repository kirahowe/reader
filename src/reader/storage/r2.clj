(ns reader.storage.r2
  "Cloudflare R2 backend for the storage seam, via Cognitect aws-api (R2 is
   S3-compatible). One S3 client per store, pointed at the account's R2 endpoint;
   get/put a single object by key. Loaded on demand by `reader.storage/open` only
   when `:backend :r2`, so dev/test never pull the AWS deps at runtime."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [reader.storage :as storage])
  (:import (java.io InputStream)))

(defn- s3-client [{:keys [account-id access-key secret region] :or {region "auto"}}]
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
      key)))

(defn ->store
  "An R2-backed Blobs store. `cfg` needs :account-id :bucket :access-key :secret;
   :region defaults to R2's \"auto\". Fails fast on missing credentials so a
   misconfigured prod refuses to boot rather than dropping mail silently."
  [{:keys [account-id bucket access-key secret] :as cfg}]
  (when (some str/blank? [account-id bucket access-key secret])
    (throw (ex-info "R2 storage misconfigured (need account-id, bucket, access-key, secret)"
                    {:missing (->> {:account-id account-id :bucket bucket
                                    :access-key access-key :secret secret}
                                   (filter (comp str/blank? val))
                                   (mapv key))})))
  (log/info "storage backend r2" {:account-id account-id :bucket bucket})
  (->R2Store (s3-client cfg) bucket))
