(ns reader.handlers.tags-test
  "The per-user tag override handlers are owner-scoped: only the queue item's
   owner can add or remove its tags. The handlers are taken from the running
   system, so the wiring is exercised too."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.db.crud :as crud]
            [reader.domain.tags :as tags]
            [reader.test-support.setup :refer [with-system]]))

(defn- effective [ds qid]
  (set (map :label (tags/effective-for-queue-item ds (crud/by-id ds :queue-items qid)))))

(deftest add-handler-owner-scoped-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          add (:reader.handlers.tags/add system)
          aff (crud/create! ds :affiliations {:name "A" :slug "a" :type "blog"})
          art (crud/create! ds :articles {:affiliation-id (:affiliations/id aff)
                                          :title "T" :slug "t" :canonical-url "https://e.test/t"})
          u1  (crud/create! ds :users {:email "u1@example.com"})
          u2  (crud/create! ds :users {:email "u2@example.com"})
          qid (:queue-items/id (crud/create! ds :queue-items
                                             {:user-id (:users/id u1)
                                              :readable-type "article" :readable-id (:articles/id art)}))]
      (testing "the owner can add a tag, which becomes effective"
        (let [resp (add {:user-id     (:users/id u1)
                         :path-params {:id (str qid)}
                         :params      {"label" "Robotics"}})]
          (is (= 303 (:status resp)))
          (is (= #{"robotics"} (effective ds qid)))))
      (testing "another user gets 404 and cannot affect the item"
        (let [resp (add {:user-id     (:users/id u2)
                         :path-params {:id (str qid)}
                         :params      {"label" "Sneaky"}})]
          (is (= 404 (:status resp)))
          (is (= #{"robotics"} (effective ds qid))))))))

(deftest remove-handler-owner-scoped-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          rm  (:reader.handlers.tags/remove system)
          aff (crud/create! ds :affiliations {:name "A" :slug "a" :type "blog"})
          art (crud/create! ds :articles {:affiliation-id (:affiliations/id aff)
                                          :title "T" :slug "t" :canonical-url "https://e.test/t2"})
          u1  (crud/create! ds :users {:email "r1@example.com"})
          u2  (crud/create! ds :users {:email "r2@example.com"})
          aid (:articles/id art)
          qid (:queue-items/id (crud/create! ds :queue-items
                                             {:user-id (:users/id u1)
                                              :readable-type "article" :readable-id aid}))
          tag (tags/find-or-create-label! ds "physics")]
      (tags/set-baseline! ds "article" aid [{:tag-id (:id tag)}])
      (testing "a non-owner cannot remove a tag"
        (let [resp (rm {:user-id     (:users/id u2)
                        :path-params {:id (str qid) :tag-id (str (:id tag))}})]
          (is (= 404 (:status resp)))
          (is (= #{"physics"} (effective ds qid)))))
      (testing "the owner can remove (suppress) a baseline tag"
        (let [resp (rm {:user-id     (:users/id u1)
                        :path-params {:id (str qid) :tag-id (str (:id tag))}})]
          (is (= 303 (:status resp)))
          (is (= #{} (effective ds qid))))))))
