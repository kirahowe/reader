(ns reader.inbound
  "Inbound-email webhook domain: the contract for what the Cloudflare worker posts
   after storing a raw .eml in R2, the replay-window check, and routing a valid
   notification to an :ingest-email job for the addressed user. Signature
   verification lives in reader.web.signature; this is the payload + freshness +
   enqueue logic, kept out of the HTTP handler."
  (:require [malli.core :as m]
            [reader.domain.inboxes :as inboxes]
            [reader.jobs :as jobs]
            [reader.storage :as storage]))

(def Payload
  "The signed notification the worker posts. message-id threads through to the
   job as the idempotency key (so a redelivery doesn't double-ingest); from /
   subject / size are hints the .eml is still authoritative for."
  [:map {:closed true}
   [:alias      [:string {:min 1}]]
   [:r2-key     [:string {:min 1}]]
   [:message-id [:string {:min 1}]]
   [:from       [:string {:min 1}]]
   [:subject    :string]
   [:size       [:int {:min 0}]]])

(defn valid-payload? [payload] (m/validate Payload payload))

(def ^:private fresh-window-secs 300)

(defn fresh?
  "True iff `ts` (unix seconds) is within the replay window of `now` (unix
   seconds). A small window bounds how long a captured (timestamp, signature)
   pair stays replayable; the signature binds the timestamp to the body."
  [now ts]
  (and (int? ts) (<= (abs (- (long now) (long ts))) fresh-window-secs)))

(defn accept!
  "Route a validated payload: resolve its alias to a user and enqueue an
   :ingest-email job carrying the r2 key + message id. Returns the job row, or
   nil when the alias matches no user (mail addressed to nobody)."
  [ds {:keys [alias r2-key message-id]}]
  (when-let [inbox (inboxes/by-alias ds alias)]
    (jobs/enqueue! ds "ingest-email"
                   {:user-id    (str (:email-inboxes/user-id inbox))
                    :r2-key     r2-key
                    :message-id message-id})))

(defn deliver!
  "Direct inbound delivery of a raw `.eml` for `alias` — the dev/test/PR path
   with no worker, R2, or HMAC. Resolves the alias to a user, stores the bytes
   via the storage abstraction, and enqueues the *same* :ingest-email job the prod
   webhook does (the Message-ID is parsed from the .eml downstream). Returns the
   job, or nil for an unknown alias."
  [ds store alias raw-bytes]
  (when-let [inbox (inboxes/by-alias ds alias)]
    (let [k (str "inbox/" (random-uuid) ".eml")]
      (storage/put-object store k raw-bytes "message/rfc822")
      (jobs/enqueue! ds "ingest-email"
                     {:user-id (str (:email-inboxes/user-id inbox)) :r2-key k}))))
