(ns reader.users
  "User domain logic. Users are provisioned from a verified Hanko identity; the
   allowlist decision lives in `reader.auth` and the orchestration in the auth
   middleware, so this namespace is just the reads and the write."
  (:require [reader.db.crud :as crud]))

(defn by-hanko-id [ds hanko-id]
  (when hanko-id (crud/find-1 ds :users {:hanko-id hanko-id})))

(defn by-email [ds email]
  (when email (crud/find-1 ds :users {:email email})))

(defn find-by-identity!
  "The existing user for a verified identity, or nil. Looks up by Hanko subject
   first; failing that, by email — reconciling a returning address to its current
   subject by backfilling `hanko_id`. The email path covers a Hanko account
   re-created under a new subject (same address), which would otherwise collide
   on the unique email."
  [ds {:keys [hanko-id email]}]
  (or (by-hanko-id ds hanko-id)
      (when-let [u (by-email ds email)]
        ;; Only backfill a real, changed subject — never overwrite a stored
        ;; hanko_id with nil (the UNIQUE constraint permits multiple NULLs, so
        ;; that wouldn't even error).
        (if (and hanko-id (not= hanko-id (:users/hanko-id u)))
          (crud/update! ds :users (:users/id u) {:hanko-id hanko-id})
          u))))

(defn create!
  "Provision a new user from verified identity attrs ({:hanko-id :email
   :display-name})."
  [ds attrs]
  (crud/create! ds :users attrs))
