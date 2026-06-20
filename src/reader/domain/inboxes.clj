(ns reader.domain.inboxes
  "Per-user inbound email aliases. Each user has a friendly-but-unguessable alias
   address — a haikunator `adjective-noun` name plus a random token, e.g.
   `aged-morning-k3f9@themiscellany.app`. Newsletters forwarded to it land in
   that user's queue (wired by the inbound webhook + :ingest-email job).

   The alias is a capability — knowing it lets a sender reach the queue — so the
   words are just for memorability; the unguessability comes from the SecureRandom
   token suffix (~21 bits on top of the word pair), never from identity. The
   stored alias is the full `name@domain` recipient address, so the inbound
   webhook can match an incoming recipient against it directly."
  (:require [haikunator :refer [haikunate]]
            [reader.db.crud :as crud])
  (:import (java.security SecureRandom)))

(defonce ^:private rng (SecureRandom.))

(def ^:private token-alphabet "0123456789abcdefghijklmnopqrstuvwxyz")
;; 4 base36 chars (~21 bits) on top of the word pair — short to type, still
;; unguessable at our scale. Bump back up if the collision-retry starts firing.
(def ^:private token-length 4)
(def ^:private max-attempts 5)

(defn- secure-token
  "An `n`-char base36 token from a CSPRNG — the unguessable part of an alias."
  [n]
  (let [sb (StringBuilder.)]
    (dotimes [_ n]
      (.append sb (.charAt token-alphabet (.nextInt rng (count token-alphabet)))))
    (str sb)))

(defn gen-alias
  "A friendly-but-unguessable recipient address at `domain`, e.g.
   `aged-morning-k3f9@themiscellany.app`: a haikunator word pair plus a
   SecureRandom token."
  [domain]
  (str (haikunate {:token-length 0}) "-" (secure-token token-length) "@" domain))

(defn current
  "The user's current inbox row, or nil. Tolerant of more than one (a rare
   provisioning race could leave duplicates, all routing to the same user);
   returns the earliest so the displayed address stays stable."
  [ds user-id]
  (->> (crud/find-many ds :email-inboxes {:user-id user-id})
       (sort-by :email-inboxes/created-at)
       first))

(defn- unique-violation? [^java.sql.SQLException e]
  (= "23505" (.getSQLState e)))

(defn- provision!
  "Insert a fresh inbox for `user-id`. Word-based aliases carry far less entropy
   than a full random token, so a clash on the alias unique index is plausible as
   the user base grows — regenerate and retry a few times before giving up."
  [ds user-id domain]
  (loop [attempt 1]
    (let [result (try
                   (crud/create! ds :email-inboxes {:user-id user-id :alias (gen-alias domain)})
                   (catch java.sql.SQLException e
                     (if (and (unique-violation? e) (< attempt max-attempts))
                       ::collision
                       (throw e))))]
      (if (= ::collision result)
        (recur (inc attempt))
        result))))

(defn find-or-provision!
  "The user's inbox row, creating one at `domain` if they have none. Idempotent.
   `domain` is the configured inbound-email domain; the stored alias is the full
   `name@domain` address newsletters are forwarded to."
  [ds user-id domain]
  (or (current ds user-id)
      (provision! ds user-id domain)))

(defn rotate!
  "Retire all of `user-id`'s current alias rows and mint a fresh one at `domain`,
   returning the new row. The old alias stops resolving immediately, so mail to it
   is refused — the point of rotating. Deliberately not one transaction:
   `provision!` recovers from a (vanishingly rare) alias collision by retrying a
   fresh INSERT, which an aborted transaction would forbid; and deleting before
   provisioning means a mid-failure leaves no row, which the next visit self-heals
   via `find-or-provision!` rather than showing a half-dead alias."
  [ds user-id domain]
  (doseq [{:email-inboxes/keys [id]} (crud/find-many ds :email-inboxes {:user-id user-id})]
    (crud/delete! ds :email-inboxes id))
  (provision! ds user-id domain))

(defn by-alias
  "The inbox row for a full recipient `address`, or nil — how the inbound webhook
   resolves an incoming recipient to its owning user. `alias` is unique."
  [ds address]
  (when address (crud/find-1 ds :email-inboxes {:alias address})))
