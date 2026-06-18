(ns reader.jobs.worker
  "Integrant-owned background worker: a single thread that polls the durable
   jobs table, dispatches each claimed job to its registered handler, and
   settles it (complete! / fail!). Per principle #16 the jobs table is the
   source of truth — the worker is just the loop, so a crash mid-job is
   recovered by the next lease.

   `:handlers` is an injected {queue-name -> (fn [ds payload])} map (function
   injection — tests pass a stub registry, the real ingest handler is wired in
   reader.ingest). The loop drains every queue, then sleeps `poll-ms` only when
   a full pass found nothing."
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [reader.jobs :as jobs]))

(defn- run-one!
  "Claim and run at most one job for `queue-name`. Returns true iff a job ran."
  [ds queue-name handler {:keys [lease-secs max-attempts backoff-base-secs]}]
  (when-let [job (jobs/claim-next! ds queue-name {:lease-secs lease-secs})]
    (try
      (handler ds (:jobs/payload job))
      (jobs/complete! ds job)
      (catch Throwable t
        (log/error t "job failed" {:queue queue-name :job-id (:jobs/id job)})
        ;; A handler can flag a permanent failure (ex-data :fatal?) so it lands
        ;; in `failed` immediately; otherwise jobs/fail! retries with backoff and
        ;; gives up (-> `failed`) once max-attempts is exhausted. The :error-class
        ;; (also from ex-data) is persisted so the UI can branch on the reason.
        (jobs/fail! ds job (or (ex-message t) "error")
                    {:fatal?            (boolean (:fatal? (ex-data t)))
                     :error-class       (:error-class (ex-data t))
                     :max-attempts      max-attempts
                     :backoff-base-secs backoff-base-secs})))
    true))

(defn- drain!
  "One pass over every queue; true iff any job ran."
  [ds handlers opts]
  (reduce-kv (fn [worked? queue-name handler]
               (if (run-one! ds queue-name handler opts) true worked?))
             false
             handlers))

(defn- poll-loop! [ds handlers {:keys [poll-ms] :as opts} running?]
  (while @running?
    (let [worked? (try (drain! ds handlers opts)
                       (catch Throwable t (log/error t "worker poll error") false))]
      (when (and @running? (not worked?))
        (Thread/sleep poll-ms)))))

(defmethod ig/init-key :reader.jobs/worker
  [_ {:keys [datasource handlers poll-ms lease-secs max-attempts backoff-base-secs]
      :or   {poll-ms 1000 lease-secs 60 max-attempts 5 backoff-base-secs 10}}]
  (log/info "worker starting" {:queues (keys handlers) :poll-ms poll-ms :max-attempts max-attempts})
  (let [running? (atom true)
        opts     {:poll-ms poll-ms :lease-secs lease-secs
                  :max-attempts max-attempts :backoff-base-secs backoff-base-secs}
        thread   (async/thread (poll-loop! datasource handlers opts running?))]
    {:running? running? :thread thread}))

(defmethod ig/halt-key! :reader.jobs/worker [_ {:keys [running? thread]}]
  (log/info "worker stopping")
  (reset! running? false)
  (async/<!! thread))
