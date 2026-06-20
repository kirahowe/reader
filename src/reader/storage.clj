(ns reader.storage
  "Blob storage abstraction — `:reader.storage/store`. Holds opaque bytes by key:
   raw inbound `.eml` files now, PDFs later. Pluggable per environment via the
   `:backend` discriminator, so prod uses the real thing and dev/test/PR tenants
   use easy local stand-ins:

     :memory  in-process atom            (tests — ephemeral, no fs)
     :file    a local directory          (dev / PR tenants — no uploads, inspectable)
     :r2      Cloudflare R2              (prod — see reader.storage.r2)

   Consumers depend only on the `Blobs` protocol, so the backend swaps by config
   without touching the ingest job or any caller."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defprotocol Blobs
  (get-object [store key]
    "The bytes stored under `key` (a byte array), or nil if there's no such object.")
  (put-object [store key ^bytes bytes content-type]
    "Store `bytes` under `key` with `content-type`. Returns `key`.")
  (enabled? [store]
    "True when this store can actually read/write; false for a placeholder whose
     backend isn't configured. Lets a caller report a feature as inert (e.g. the
     settings page noting inbound email isn't wired) without attempting an op."))

;; ── in-memory (tests) ────────────────────────────────────────────────────

(deftype MemoryStore [objects]
  Blobs
  (get-object [_ key] (:bytes (get @objects key)))
  (put-object [_ key bytes content-type]
    (swap! objects assoc key {:bytes bytes :content-type content-type})
    key)
  (enabled? [_] true))

(defn memory-store
  "An in-memory Blobs store backed by an atom. For dev, tests, and seeding."
  []
  (->MemoryStore (atom {})))

;; ── local filesystem (dev / PR tenants) ──────────────────────────────────

(deftype FileStore [^java.io.File root]
  Blobs
  (get-object [_ key]
    (let [f (io/file root key)]
      (when (.isFile f)
        (with-open [in (io/input-stream f)] (.readAllBytes in)))))
  (put-object [_ key bytes _content-type]
    ;; keys are app-generated (e.g. inbox/<uuid>.eml), so the nested path is safe
    ;; to create directly; content-type isn't persisted (the bytes are all we read).
    (let [f (io/file root key)]
      (io/make-parents f)
      (with-open [out (io/output-stream f)] (.write out ^bytes bytes))
      key))
  (enabled? [_] true))

(defn file-store
  "A Blobs store rooted at directory `root` (created if absent)."
  [root]
  (when (str/blank? root)
    (throw (ex-info "file storage needs a :root directory" {})))
  (let [dir (io/file root)]
    (.mkdirs dir)
    (->FileStore dir)))

;; ── disabled (an unconfigured optional backend) ──────────────────────────

(deftype DisabledStore [reason]
  Blobs
  (get-object [_ _] (throw (ex-info "blob storage is not configured" {:reason reason})))
  (put-object [_ _ _ _] (throw (ex-info "blob storage is not configured" {:reason reason})))
  (enabled? [_] false))

(defn disabled-store
  "A store that throws on use — for an optional backend whose config is absent,
   so the system boots (feature inert) instead of failing at startup."
  [reason]
  (->DisabledStore reason))

;; ── abstraction ─────────────────────────────────────────────────────────────────

(defn open
  "Construct a Blobs store from a backend config. `:memory` and `:file` are the
   local stand-ins; `:r2` is Cloudflare R2 in prod (loaded on demand so the
   dev/test path never pulls the AWS deps). Fails loud on an unknown backend."
  [{:keys [backend] :as cfg}]
  (case backend
    :memory (memory-store)
    :file   (file-store (:root cfg))
    :r2     ((requiring-resolve 'reader.storage.r2/->store) cfg)
    (throw (ex-info "unknown storage backend" {:backend backend}))))

(defmethod ig/init-key :reader.storage/store [_ {:keys [backend] :as cfg}]
  (log/info "storage starting" {:backend backend})
  (open cfg))
