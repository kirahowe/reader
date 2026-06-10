(ns reader.jobs-test
  (:require [clojure.test :refer [deftest is testing]]
            [reader.db.crud :as crud]
            [reader.jobs :as jobs]
            [reader.test-support.setup :refer [with-system]]))

(deftest enqueue!-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          row (jobs/enqueue! ds "emails" {:to "x@reader.test" :subject "Hi"})]

      (testing "the row is persisted in 'pending' state"
        (is (uuid? (:jobs/id row)))
        (is (= "emails"  (:jobs/queue-name row)))
        (is (= "pending" (:jobs/state row)))
        (is (= 0         (:jobs/attempts row)))
        (is (= {:to "x@reader.test" :subject "Hi"} (:jobs/payload row))))

      (testing "the row round-trips via crud/by-id"
        (is (= row (crud/by-id ds :jobs (:jobs/id row))))))))

(deftest claim-next!-test
  (with-system [system]
    (let [ds (:reader.db/datasource system)]

      (testing "returns nil on an empty queue"
        (is (nil? (jobs/claim-next! ds "empty"))))

      (testing "claims and transitions the next pending job"
        (let [enqueued (jobs/enqueue! ds "emails" {:to "a@x.test"})
              claimed  (jobs/claim-next! ds "emails")]
          (is (= (:jobs/id enqueued) (:jobs/id claimed)))
          (is (= "in_progress" (:jobs/state claimed)))
          (is (= 1 (:jobs/attempts claimed)))
          (is (inst? (:jobs/locked-until claimed)))))

      (testing "only returns jobs from the named queue"
        (jobs/enqueue! ds "thumbnails" {:url "x"})
        (is (nil? (jobs/claim-next! ds "emails"))
            "thumbnails job exists but we asked for emails")
        (is (some? (jobs/claim-next! ds "thumbnails"))
            "...and the thumbnails one is still claimable"))

      (testing "doesn't return jobs whose run-at is in the future"
        (let [future-job (jobs/enqueue! ds "delayed" {:n 1}
                                        {:run-at (java.time.Instant/parse
                                                  "2099-01-01T00:00:00Z")})]
          (is (nil? (jobs/claim-next! ds "delayed")))
          (is (= "pending"
                 (:jobs/state (crud/by-id ds :jobs (:jobs/id future-job))))))))))

(deftest reclaims-expired-leases-test
  (with-system [system]
    (let [ds       (:reader.db/datasource system)
          ;; lease for 0 seconds — expired the moment it's set
          enqueued (jobs/enqueue! ds "stale" {:n 1})
          claim-1  (jobs/claim-next! ds "stale" {:lease-secs 0})
          claim-2  (jobs/claim-next! ds "stale")]

      (testing "a second worker can claim a job whose lease has expired"
        (is (= (:jobs/id enqueued) (:jobs/id claim-1)))
        (is (= (:jobs/id enqueued) (:jobs/id claim-2)))
        (is (= 2 (:jobs/attempts claim-2))
            "attempts bumps again on re-claim")))))

(deftest complete!-test
  (with-system [system]
    (let [ds      (:reader.db/datasource system)
          claimed (do (jobs/enqueue! ds "emails" {:to "c@x.test"})
                      (jobs/claim-next! ds "emails"))
          done    (jobs/complete! ds claimed)]

      (testing "transitions to 'done' and clears the lease"
        (is (= "done" (:jobs/state done)))
        (is (nil? (:jobs/locked-until done))))

      (testing "the change is persisted"
        (is (= "done" (:jobs/state (crud/by-id ds :jobs (:jobs/id claimed))))))

      (testing "won't double-settle an already-done job"
        (is (nil? (jobs/complete! ds claimed)))))))

(deftest complete!-rejects-unclaimed-test
  (with-system [system]
    (let [ds      (:reader.db/datasource system)
          pending (jobs/enqueue! ds "q" {})]

      (testing "complete! on a never-claimed job is a no-op"
        (is (nil? (jobs/complete! ds pending)))
        (is (= "pending"
               (:jobs/state (crud/by-id ds :jobs (:jobs/id pending)))))))))

(deftest fail!-test
  (with-system [system]
    (let [ds (:reader.db/datasource system)]

      (testing "by default, transitions back to 'pending' but backs off the retry"
        (let [claimed (do (jobs/enqueue! ds "retries" {:n 1})
                          (jobs/claim-next! ds "retries"))
              failed  (jobs/fail! ds claimed "transient")]
          (is (= "pending"   (:jobs/state failed)))
          (is (= "transient" (:jobs/last-error failed)))
          (is (nil? (:jobs/locked-until failed)))

          (testing "the retry is delayed by backoff, so claim-next! won't reclaim immediately"
            (is (nil? (jobs/claim-next! ds "retries"))))))

      (testing "with backoff disabled the retry is immediately claimable, bumping attempts"
        (let [claimed (do (jobs/enqueue! ds "retries2" {:n 1})
                          (jobs/claim-next! ds "retries2"))
              _       (jobs/fail! ds claimed "transient" {:backoff-base-secs 0})
              reclaim (jobs/claim-next! ds "retries2")]
          (is (= (:jobs/id claimed) (:jobs/id reclaim)))
          (is (= 2 (:jobs/attempts reclaim)))))

      (testing "gives up (-> 'failed') once max-attempts is reached"
        (let [claimed (do (jobs/enqueue! ds "giveup" {:n 1})
                          (jobs/claim-next! ds "giveup"))      ; attempts -> 1
              failed  (jobs/fail! ds claimed "transient" {:max-attempts 1})]
          (is (= "failed" (:jobs/state failed)) "attempts (1) >= max-attempts (1)")
          (is (nil? (jobs/claim-next! ds "giveup")))))

      (testing "with :fatal? true, transitions to 'failed' (terminal)"
        (let [claimed (do (jobs/enqueue! ds "emails" {:to "f@x.test"})
                          (jobs/claim-next! ds "emails"))
              failed  (jobs/fail! ds claimed "smtp refused" {:fatal? true})]
          (is (= "failed" (:jobs/state failed)))
          (is (nil? (jobs/claim-next! ds "emails"))
              "...and it's NOT picked back up by claim-next!")))

      (testing "won't settle a job whose lease was reclaimed by another worker"
        (jobs/enqueue! ds "q" {})
        (let [orig-claim (jobs/claim-next! ds "q" {:lease-secs 0})]
          (jobs/claim-next! ds "q")   ; another worker reclaims
          (is (nil? (jobs/fail! ds orig-claim "I'm too late"))
              "orig's fail! is a no-op once the new lease is in place"))))))
