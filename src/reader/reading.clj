(ns reader.reading
  "The per-user reading queue. `queue_items` is the per-user relation
   (`user_id`); the readables it points at are shared. So removing something
   from a queue archives the queue item — it never touches the shared readable
   or any other user's queue. The queue view joins each item to its normalized
   readable (see reader.readables/catalog-of)."
  (:require [reader.db.crud :as crud]
            [reader.readables :as readables]))

(def ^:private readable-type->type
  "queue_items.readable_type string -> the :type keyword reader.readables uses."
  {"article" :article "paper" :paper "newsletter_issue" :newsletter-issue})

(defn queue
  "`user-id`'s active (non-archived) queue, newest first. Each entry is a
   normalized readable (reader.readables) plus :queue-item-id, :state, and
   :added-at. Reads only the readables this user has queued, not the whole
   catalog. Skips a queue item whose readable has since been removed."
  [ds user-id]
  (let [items  (->> (crud/find-many ds :queue-items {:user-id user-id})
                    (remove (comp #{"archived"} :queue-items/state))
                    (sort-by :queue-items/added-at)
                    reverse)
        refs   (map (fn [{:queue-items/keys [readable-type readable-id]}]
                      [(readable-type->type readable-type) readable-id])
                    items)
        by-ref (into {} (map (juxt (juxt :type :id) identity))
                     (readables/catalog-of ds refs))]
    (keep (fn [{:queue-items/keys [id state added-at readable-type readable-id]}]
            (when-let [item (by-ref [(readable-type->type readable-type) readable-id])]
              (assoc item :queue-item-id id :state state :added-at added-at)))
          items)))

(defn enqueue!
  "Add a readable to `user-id`'s queue as unread. Idempotent on the readable:
   re-adding one already present (including an archived one) brings it back to
   unread at the top of the list rather than failing the
   (user, readable_type, readable_id) unique constraint. `readable-type` is the
   stored string (\"article\" / \"paper\" / \"newsletter_issue\"); `via` records
   provenance on first add."
  ([ds user-id readable-type readable-id] (enqueue! ds user-id readable-type readable-id {}))
  ([ds user-id readable-type readable-id via]
   (crud/upsert! ds :queue-items
                 {:user-id       user-id
                  :readable-type readable-type
                  :readable-id   readable-id
                  :via           via}
                 [:user-id :readable-type :readable-id]
                 {:state "unread" :added-at [:now]})))

(defn- owned
  "`user-id`'s queue item by id, or nil when it's missing or another user's.
   The single owner-scope check the state-transition verbs share."
  [ds user-id queue-item-id]
  (when-let [item (crud/by-id ds :queue-items queue-item-id)]
    (when (= user-id (:queue-items/user-id item))
      item)))

(defn- start-reading!
  "On first open, move `user-id`'s still-`unread` queue item to `reading` and stamp
   `started-at`. Atomic and owner-scoped via the WHERE; returns the updated row, or
   nil when the item was no longer unread (already reading/read/archived)."
  [ds user-id queue-item-id]
  (crud/update-where! ds :queue-items
                      [:and
                       [:= :id queue-item-id]
                       [:= :user-id user-id]
                       [:= :state "unread"]]
                      {:state "reading" :started-at (java.time.Instant/now)}))

(defn- transition!
  "One atomic owner+state-scoped UPDATE: write `attrs` to `user-id`'s queue item
   `queue-item-id`, but only while it is not archived. Returns the updated row, or
   nil when the item is missing, isn't theirs, or is already archived. Gating
   ownership and the archived guard in the WHERE makes this a single round-trip and
   keeps an archived item off the active queue. Timestamps are app-side Instants —
   a HoneySQL `[:now]` would be jsonb-encoded by `crud/update-where!`."
  [ds user-id queue-item-id attrs]
  (crud/update-where! ds :queue-items
                      [:and
                       [:= :id queue-item-id]
                       [:= :user-id user-id]
                       [:not= :state "archived"]]
                      attrs))

(defn archive!
  "Archive `user-id`'s queue item (owner-scoped). Returns the updated row, or nil
   when the item doesn't exist or isn't theirs. Re-adding later reactivates it
   (see `enqueue!`)."
  [ds user-id queue-item-id]
  (transition! ds user-id queue-item-id {:state "archived"}))

(defn mark-read!
  "Mark `user-id`'s queue item read (owner-scoped); records `finished-at` and
   preserves any existing `started-at`. An archived item is left untouched
   (returns nil) so reading it can't resurrect it onto the active queue."
  [ds user-id queue-item-id]
  (transition! ds user-id queue-item-id {:state "read" :finished-at (java.time.Instant/now)}))

(defn mark-unread!
  "Return `user-id`'s queue item to unread (owner-scoped); clears its start and
   finish timestamps. An archived item is left untouched (returns nil)."
  [ds user-id queue-item-id]
  (transition! ds user-id queue-item-id {:state "unread" :started-at nil :finished-at nil}))

(defn open
  "The reader payload for `user-id`'s queue item `queue-item-id`: the queue item
   joined to its full readable (`reader.readables/find-one`), or nil when the
   item is missing, isn't theirs, or its readable has since been removed. The
   readable's type is threaded from the queue row, not re-derived. Marks an
   `unread` item as `reading` on first open (stamping `started-at`), only once
   ownership and the readable's existence are confirmed so we never stamp an item
   we're about to 404."
  [ds user-id queue-item-id]
  (when-let [qi (owned ds user-id queue-item-id)]
    (when-let [readable (readables/find-one ds
                                            (readable-type->type (:queue-items/readable-type qi))
                                            (:queue-items/readable-id qi))]
      (let [qi (if (= "unread" (:queue-items/state qi))
                 (or (start-reading! ds user-id queue-item-id) qi)
                 qi)]
        {:queue-item qi :readable readable}))))
