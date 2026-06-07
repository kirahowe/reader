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

(defn archive!
  "Archive `user-id`'s queue item, scoped so a user can only archive their own.
   Returns the updated row, or nil when the item doesn't exist or isn't theirs."
  [ds user-id queue-item-id]
  (when-let [item (crud/by-id ds :queue-items queue-item-id)]
    (when (= user-id (:queue-items/user-id item))
      (crud/update! ds :queue-items queue-item-id {:state "archived"}))))
