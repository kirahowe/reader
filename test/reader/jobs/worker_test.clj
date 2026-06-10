(ns reader.jobs.worker-test
  "Worker lifecycle against a real embedded Postgres. The worker is driven
   through integrant (load-namespaces + init) with an injected stub handler
   registry, so these exercise claim -> dispatch -> complete and -> fail without
   any real job handler or network."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [reader.db.crud :as crud]
            [reader.jobs :as jobs]
            [reader.test-support.setup :refer [with-system]]))

(defn- wait-until [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond (pred)                                  true
            (> (System/currentTimeMillis) deadline) false
            :else                                   (do (Thread/sleep 20) (recur))))))

(deftest worker-processes-and-completes-test
  (with-system [system]
    (let [ds        (:reader.db/datasource system)
          processed (atom [])
          cfg       {:reader.jobs/worker
                     {:datasource ds
                      :handlers   {"test-extract" (fn [_ds payload] (swap! processed conj payload))}
                      :poll-ms    25 :lease-secs 30}}]
      (ig/load-namespaces cfg)
      (let [sys (ig/init cfg)]
        (try
          (jobs/enqueue! ds "test-extract" {:n 1})
          (jobs/enqueue! ds "test-extract" {:n 2})
          (is (wait-until #(= 2 (count @processed)) 3000) "both jobs were handled")
          (testing "payloads round-trip through jsonb; jobs settle to done"
            (is (= #{{:n 1} {:n 2}} (set @processed)))
            (is (wait-until #(every? (comp #{"done"} :jobs/state) (crud/find-many ds :jobs)) 1000)))
          (finally (ig/halt! sys)))))))

(deftest worker-records-failure-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          cfg {:reader.jobs/worker
               {:datasource ds
                :handlers   {"boom" (fn [_ _] (throw (ex-info "kaboom" {})))}
                :poll-ms    25 :lease-secs 30}}]
      (ig/load-namespaces cfg)
      (let [sys (ig/init cfg)]
        (try
          (jobs/enqueue! ds "boom" {})
          (is (wait-until #(some-> (crud/find-1 ds :jobs {:queue-name "boom"}) :jobs/last-error) 3000))
          (testing "a thrown handler is recorded as last_error (and left retryable)"
            (is (= "kaboom" (:jobs/last-error (crud/find-1 ds :jobs {:queue-name "boom"})))))
          (finally (ig/halt! sys)))))))

(deftest worker-gives-up-after-max-attempts-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          cfg {:reader.jobs/worker
               {:datasource ds
                :handlers   {"flaky" (fn [_ _] (throw (ex-info "transient" {})))}
                ;; backoff 0 -> immediate retries so the test runs fast; the
                ;; point is that max-attempts terminates the otherwise-endless
                ;; non-fatal retry loop.
                :poll-ms    25 :lease-secs 30 :max-attempts 2 :backoff-base-secs 0}}]
      (ig/load-namespaces cfg)
      (let [sys (ig/init cfg)]
        (try
          (jobs/enqueue! ds "flaky" {})
          (testing "a repeatedly-failing non-fatal job is given up as failed after max-attempts"
            (is (wait-until #(= "failed" (:jobs/state (crud/find-1 ds :jobs {:queue-name "flaky"}))) 3000))
            (is (= 2 (:jobs/attempts (crud/find-1 ds :jobs {:queue-name "flaky"})))))
          (finally (ig/halt! sys)))))))

(deftest worker-fatal-failure-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          cfg {:reader.jobs/worker
               {:datasource ds
                :handlers   {"perm" (fn [_ _] (throw (ex-info "permanent" {:fatal? true})))}
                :poll-ms    25 :lease-secs 30}}]
      (ig/load-namespaces cfg)
      (let [sys (ig/init cfg)]
        (try
          (jobs/enqueue! ds "perm" {})
          (testing "a fatal handler error lands the job in failed, not an endless retry"
            (is (wait-until #(= "failed" (:jobs/state (crud/find-1 ds :jobs {:queue-name "perm"}))) 3000)))
          (finally (ig/halt! sys)))))))
