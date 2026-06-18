(ns reader.jobs
  "Durable background job queue. Workers call `claim-next!` to lease
   the next due job (atomic via SELECT ... FOR UPDATE SKIP LOCKED),
   then `complete!` or `fail!` to settle it. A lease has a TTL — a
   crashed worker's job is reclaimable once `locked-until` passes."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [reader.db.crud :as crud]))

(defn enqueue!
  "Insert a new pending job on `queue-name`. Optional `:run-at` (an
   Instant) schedules it for later.

   Payload shape is the caller's responsibility — per principle #13,
   validation happens at boundaries. When an HTTP handler or inbound
   webhook becomes a caller, that handler Malli-validates the payload
   before passing it in."
  ([ds queue-name payload] (enqueue! ds queue-name payload {}))
  ([ds queue-name payload {:keys [run-at]}]
   (crud/create! ds :jobs (cond-> {:queue-name queue-name :payload payload}
                            run-at (assoc :run-at run-at)))))

(defn claim-next!
  "Lease the next pending or stale-leased job from `queue-name` whose
   `run-at` has elapsed. Transitions state to `\"in_progress\"`, bumps
   `attempts`, and sets `locked-until` to `now() + lease-secs`. Returns
   the leased row, or nil if nothing is claimable.

   Concurrency-safe: `SELECT ... FOR UPDATE SKIP LOCKED` inside a
   transaction so parallel workers can never pick the same row."
  ([ds queue-name] (claim-next! ds queue-name {}))
  ([ds queue-name {:keys [lease-secs] :or {lease-secs 60}}]
   (jdbc/with-transaction [tx ds]
     (when-let [{:jobs/keys [id]}
                (jdbc/execute-one!
                 tx
                 (sql/format
                  {:select   [:id]
                   :from     [:jobs]
                   :where    [:and
                              [:= :queue-name queue-name]
                              [:<= :run-at [:raw "now()"]]
                              [:or
                               [:= :state "pending"]
                               [:and
                                [:= :state "in_progress"]
                                [:<= :locked-until [:raw "now()"]]]]]
                   ;; run-at is the schedule order; created-at + id break
                   ;; ties deterministically (now() is transaction-stable,
                   ;; so same-tx enqueues share run-at AND created-at, and
                   ;; id — a uuid — is the only guaranteed-unique tiebreak).
                   :order-by [[:run-at :asc] [:created-at :asc] [:id :asc]]
                   :limit    1
                   :for      [:update :skip-locked]})
                 crud/opts)]
       (jdbc/execute-one!
        tx
        (sql/format {:update    :jobs
                     :set       {:state        "in_progress"
                                 :attempts     [:+ :attempts 1]
                                 ;; lease-secs binds as a parameter, never
                                 ;; interpolated into the SQL string.
                                 :locked-until [:+ [:raw "now()"]
                                                [:* lease-secs [:raw "interval '1 second'"]]]
                                 :updated-at   [:raw "now()"]}
                     :where     [:= :id id]
                     :returning [:*]})
        crud/opts)))))

(defn- settle!
  "Common UPDATE path for `complete!`/`fail!`. Scopes the update to the
   exact lease the caller is holding (id + attempts as observed at
   claim time + state still in_progress), so a worker whose lease was
   reclaimed by another worker can't clobber the new owner."
  [ds {:jobs/keys [id attempts]} changes]
  (jdbc/execute-one! ds
                     (sql/format {:update    :jobs
                                  :set       (assoc changes :updated-at [:raw "now()"])
                                  :where     [:and
                                              [:= :id id]
                                              [:= :state "in_progress"]
                                              [:= :attempts attempts]]
                                  :returning [:*]})
                     crud/opts))

(defn complete!
  "Mark the leased `job` done and release the lease. Takes the row
   returned by `claim-next!`. Returns the updated row, or nil if our
   lease no longer holds (expired and reclaimed, or already settled)."
  [ds job]
  (settle! ds job {:state "done" :locked-until nil}))

(defn fail!
  "Record a failed run on the leased `job` (the row returned by `claim-next!`)
   with `error-msg` and optional `:error-class` (the handler's ex-data keyword,
   persisted so consumers can branch on *why* without parsing the message). The
   job lands in `\"failed\"` when the error is fatal (`{:fatal? true}`) or it has
   exhausted `:max-attempts`; otherwise it is rescheduled for a later retry with
   exponential backoff (`:backoff-base-secs` * 2^(attempts-1)) so a flaky source
   isn't hammered in a tight loop. Returns nil if our lease no longer holds."
  ([ds job error-msg] (fail! ds job error-msg {}))
  ([ds job error-msg {:keys [fatal? max-attempts backoff-base-secs error-class]
                      :or   {max-attempts 5 backoff-base-secs 10}}]
   (let [attempts (:jobs/attempts job)
         give-up? (or fatal? (>= attempts max-attempts))
         backoff  (long (* backoff-base-secs (Math/pow 2 (dec attempts))))]
     (settle! ds job
              (cond-> {:state        (if give-up? "failed" "pending")
                       :last-error   error-msg
                       :error-class  (some-> error-class name)
                       :locked-until nil}
                (not give-up?)
                (assoc :run-at [:+ [:raw "now()"]
                                [:* backoff [:raw "interval '1 second'"]]]))))))
