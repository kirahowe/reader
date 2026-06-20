(ns reader.domain.reading-test
  "Integration tests for the per-user reading queue: queue assembly + filtering,
   enqueue, and owner-scoped archive. Real embedded Postgres via with-system."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.db.crud :as crud]
            [reader.domain.readables :as readables]
            [reader.domain.reading :as reading]
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

(deftest state-lifecycle-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          uid (mk-user ds "s@x.test")
          oid (mk-user ds "o@x.test")
          aid (mk-article ds "Stateful" "https://x.test/s1")
          qid (:queue-items/id (reading/enqueue! ds uid "article" aid))]

      (testing "a fresh queue item is unread with no lifecycle timestamps"
        (let [row (crud/by-id ds :queue-items qid)]
          (is (= "unread" (:queue-items/state row)))
          (is (nil? (:queue-items/started-at row)))
          (is (nil? (:queue-items/finished-at row)))))

      (testing "mark-read! records a finish and persists the transition"
        (let [row (reading/mark-read! ds uid qid)]
          (is (= "read" (:queue-items/state row)))
          (is (some? (:queue-items/finished-at row)) "finish stamped")
          (is (= "read" (:queue-items/state (crud/by-id ds :queue-items qid)))
              "the transition is persisted")))

      (testing "mark-unread! resets the state and clears both timestamps"
        (let [row (reading/mark-unread! ds uid qid)]
          (is (= "unread" (:queue-items/state row)))
          (is (nil? (:queue-items/started-at row)))
          (is (nil? (:queue-items/finished-at row)))))

      (testing "transitions are owner-scoped and tolerate a missing item"
        (is (nil? (reading/mark-read! ds oid qid)) "another user's item -> nil")
        (is (= "unread" (:queue-items/state (crud/by-id ds :queue-items qid)))
            "their item is untouched")
        (is (nil? (reading/mark-read! ds uid (random-uuid))) "missing item -> nil")))))

(deftest archived-item-immune-to-read-transitions-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          uid (mk-user ds "arch@x.test")
          aid (mk-article ds "Buried" "https://x.test/buried")
          qid (:queue-items/id (reading/enqueue! ds uid "article" aid))]

      (is (some? (reading/archive! ds uid qid)) "the item starts out archived")

      (testing "mark-read! cannot resurrect an archived item"
        (is (nil? (reading/mark-read! ds uid qid)) "no row matches -> nil")
        (is (= "archived" (:queue-items/state (crud/by-id ds :queue-items qid)))
            "it stays archived, off the active queue"))

      (testing "mark-unread! cannot resurrect an archived item"
        (is (nil? (reading/mark-unread! ds uid qid)) "no row matches -> nil")
        (is (= "archived" (:queue-items/state (crud/by-id ds :queue-items qid)))
            "it stays archived, off the active queue")))))

(deftest find-one-test
  (with-system [system]
    (let [ds    (:reader.db/datasource system)
          aff   (crud/create! ds :affiliations {:name "The Source" :slug "the-source" :type "magazine"})
          art   (crud/create! ds :articles {:title          "On Engines"
                                            :slug           "on-engines"
                                            :canonical-url  "https://x.test/engines"
                                            :abstract       "A note."
                                            :affiliation-id (:affiliations/id aff)})
          found (readables/find-one ds :article (:articles/id art))]
      (testing "find-one joins the normalized item (title + source) under :item"
        (is (= "On Engines" (-> found :item :title)))
        (is (= {:name "The Source" :slug "the-source"} (-> found :item :source)))
        (is (= [] (-> found :item :authors)) "no authorship -> empty byline"))
      (testing "find-one carries the raw row under :row for type-specific fields"
        (is (= "A note." (-> found :row :articles/abstract)))
        (is (= (:articles/id art) (-> found :row :articles/id))))
      (testing "an unknown type or a missing id is nil"
        (is (nil? (readables/find-one ds :article (random-uuid))))
        (is (nil? (readables/find-one ds :bogus (:articles/id art))))))))

(deftest open-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          uid (mk-user ds "op@x.test")
          oid (mk-user ds "ox@x.test")
          aid (mk-article ds "Openable" "https://x.test/open")
          qid (:queue-items/id (reading/enqueue! ds uid "article" aid))]
      (testing "open returns the queue item joined to its full readable, promoting it to reading"
        (let [{:keys [queue-item readable]} (reading/open ds uid qid)]
          (is (= qid (:queue-items/id queue-item)))
          (is (= "Openable" (-> readable :item :title)))
          (is (= aid (-> readable :row :articles/id)) "the raw row is threaded through")
          (is (= "reading" (:queue-items/state queue-item)) "first open moves it to reading")
          (is (some? (:queue-items/started-at queue-item)) "first open stamps started-at"))
        (let [row (crud/by-id ds :queue-items qid)]
          (is (= "reading" (:queue-items/state row)) "the transition is persisted")
          (is (some? (:queue-items/started-at row)) "started-at is persisted")))

      (testing "re-opening keeps it reading without bumping started-at"
        (let [started (:queue-items/started-at (crud/by-id ds :queue-items qid))
              {:keys [queue-item]} (reading/open ds uid qid)]
          (is (= "reading" (:queue-items/state queue-item)) "state does not regress")
          (is (= started (:queue-items/started-at queue-item)) "started-at is not bumped")
          (is (= started (:queue-items/started-at (crud/by-id ds :queue-items qid)))
              "and the persisted started-at is unchanged")))

      (testing "open is owner-scoped and nil for a missing item, leaving the row untouched"
        (let [oqid (:queue-items/id (reading/enqueue! ds uid "article"
                                                      (mk-article ds "Untouched" "https://x.test/untouched")))]
          (is (nil? (reading/open ds oid oqid)) "another user's item -> nil")
          (is (nil? (reading/open ds uid (random-uuid))) "missing -> nil")
          (let [row (crud/by-id ds :queue-items oqid)]
            (is (= "unread" (:queue-items/state row)) "an owner-mismatched open leaves it unread")
            (is (nil? (:queue-items/started-at row)) "and never stamps started-at")))))))
