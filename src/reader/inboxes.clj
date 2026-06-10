(ns reader.inboxes
  "Per-user inbound email aliases. Each user has an unguessable alias address;
   newsletters forwarded to it land in that user's queue (wired by the inbound
   webhook + :ingest-email job). The alias is a capability — knowing it lets a
   sender reach the queue — so the local part is random, never derived from
   identity. The stored alias is the full `token@domain` recipient address, so
   the inbound webhook can match an incoming recipient against it directly."
  (:require [reader.db.crud :as crud])
  (:import (java.security SecureRandom)))

(defonce ^:private rng (SecureRandom.))

(defn- gen-token
  "An unguessable local part: `r-` + 32 hex chars (16 bytes of CSPRNG entropy)."
  []
  (let [b (byte-array 16)]
    (.nextBytes rng b)
    (apply str "r-" (map #(format "%02x" (bit-and % 0xff)) b))))

(defn- for-user
  "The user's existing inbox row, or nil. Tolerant of more than one (a rare
   provisioning race could leave duplicates, all routing to the same user);
   returns the earliest so the displayed address stays stable."
  [ds user-id]
  (->> (crud/find-many ds :email-inboxes {:user-id user-id})
       (sort-by :email-inboxes/created-at)
       first))

(defn find-or-provision!
  "The user's inbox row, creating one at `domain` if they have none. Idempotent.
   `domain` is the configured inbound-email domain; the stored alias is the full
   `token@domain` address newsletters are forwarded to."
  [ds user-id domain]
  (or (for-user ds user-id)
      (crud/create! ds :email-inboxes {:user-id user-id
                                       :alias   (str (gen-token) "@" domain)})))
