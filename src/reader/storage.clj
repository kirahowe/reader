(ns reader.storage
  "Blob storage seam — `:reader.storage/store`. Holds opaque bytes by key:
   raw inbound `.eml` files now, PDFs later. Prod is Cloudflare R2 (S3-compatible,
   zero egress); dev and tests run an in-memory stub so nothing reaches a real
   bucket, mirroring how embedded-postgres stands in for Neon.

   Consumers depend only on the `Blobs` protocol, so the backend is swapped by
   config (`:backend`) without touching the ingest job or any caller — the same
   seam pattern as the entity extractor and job handlers."
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defprotocol Blobs
  (get-object [store key]
    "The bytes stored under `key` (a byte array), or nil if there's no such object.")
  (put-object [store key ^bytes bytes content-type]
    "Store `bytes` under `key` with `content-type`. Returns `key`."))

(deftype MemoryStore [objects]
  Blobs
  (get-object [_ key] (:bytes (get @objects key)))
  (put-object [_ key bytes content-type]
    (swap! objects assoc key {:bytes bytes :content-type content-type})
    key))

(defn memory-store
  "An in-memory Blobs store backed by an atom. For dev, tests, and seeding."
  []
  (->MemoryStore (atom {})))

(defn open
  "Construct a Blobs store from a backend config. `:memory` is the dev/test
   stub; `:r2` lands with Slice 5 (it needs a real bucket + credentials). Fails
   loud on an unrecognized backend rather than booting a store that drops writes."
  [{:keys [backend]}]
  (case backend
    :memory (memory-store)
    (throw (ex-info "unknown storage backend" {:backend backend}))))

(defmethod ig/init-key :reader.storage/store [_ {:keys [backend] :as cfg}]
  (log/info "storage starting" {:backend backend})
  (open cfg))
