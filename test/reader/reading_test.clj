(ns reader.reading-test
  "Integration tests for the per-user reading queue: queue assembly + filtering,
   enqueue, and owner-scoped archive. Real embedded Postgres via with-system."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.db.crud :as crud]
            [reader.reading :as reading]
            [reader.test-support.setup :refer [with-system]])
  (:import (java.time Instant)))

(defn- mk-user [ds email] (:users/id (crud/create! ds :users {:email email})))

(defn- mk-article [ds title url]
  (:articles/id (crud/create! ds :articles {:title title :slug title :canonical-url url})))

(deftest queue-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          uid (mk-user ds "owner@x.test")
          oid (mk-user ds "other@x.test")
          a1  (mk-article ds "Alpha" "https://x.test/a1")
          a2  (mk-article ds "Beta"  "https://x.test/a2")
          a3  (mk-article ds "Gamma" "https://x.test/a3")]
      ;; Two active items with explicit, ordered added_at; one archived; one for
      ;; another user — so the test pins ordering and both filters.
      (crud/create! ds :queue-items {:user-id uid :readable-type "article" :readable-id a1
                                     :added-at (Instant/parse "2026-01-01T00:00:00Z")})
      (crud/create! ds :queue-items {:user-id uid :readable-type "article" :readable-id a2
                                     :added-at (Instant/parse "2026-01-02T00:00:00Z")})
      (crud/create! ds :queue-items {:user-id uid :readable-type "article" :readable-id a3
                                     :state "archived"})
      (crud/create! ds :queue-items {:user-id oid :readable-type "article" :readable-id a1})

      (testing "queue returns only the user's active items, newest first"
        (let [items (reading/queue ds uid)]
          (is (= ["Beta" "Alpha"] (map :title items))
              "archived and other users' items excluded; ordered by added_at desc")
          (is (every? :queue-item-id items) "each item carries its queue-item id")
          (is (= ["unread" "unread"] (map :state items)))))

      (testing "queue is empty for a user with nothing queued"
        (is (empty? (reading/queue ds (mk-user ds "empty@x.test"))))))))

(deftest enqueue-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          uid (mk-user ds "e@x.test")
          aid (mk-article ds "Enqueued" "https://x.test/e1")
          row (reading/enqueue! ds uid "article" aid {:source "manual"})]
      (testing "enqueue creates an unread queue item the queue then surfaces"
        (is (= "unread" (:queue-items/state row)))
        (is (= "Enqueued" (:title (first (reading/queue ds uid)))))))))

(deftest archive-test
  (with-system [system]
    (let [ds   (:reader.db/datasource system)
          uid  (mk-user ds "a@x.test")
          oid  (mk-user ds "b@x.test")
          aid  (mk-article ds "Archivable" "https://x.test/ar")
          qid  (:queue-items/id (reading/enqueue! ds uid "article" aid))]

      (testing "archiving my own item removes it from my queue"
        (is (some? (reading/archive! ds uid qid)))
        (is (empty? (reading/queue ds uid))))

      (testing "I cannot archive another user's item"
        (let [tqid (:queue-items/id (reading/enqueue! ds oid "article" aid))]
          (is (nil? (reading/archive! ds uid tqid)) "not my item -> nil")
          (is (= "unread" (:queue-items/state (crud/by-id ds :queue-items tqid)))
              "their item is untouched")))

      (testing "archiving a non-existent item is nil"
        (is (nil? (reading/archive! ds uid (random-uuid))))))))

(deftest re-add-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          uid (mk-user ds "r@x.test")
          aid (mk-article ds "Returnable" "https://x.test/re")
          q1  (reading/enqueue! ds uid "article" aid)]
      (reading/archive! ds uid (:queue-items/id q1))

      (testing "re-adding an archived item reactivates it as unread, not a duplicate"
        (is (empty? (reading/queue ds uid)) "archived: gone from the active queue")
        (let [q2 (reading/enqueue! ds uid "article" aid)]
          (is (= (:queue-items/id q1) (:queue-items/id q2))
              "same row is reactivated rather than inserting a second")
          (is (= "unread" (:queue-items/state q2)) "back to unread")
          (is (= ["Returnable"] (map :title (reading/queue ds uid)))
              "and back on the active queue"))))))
