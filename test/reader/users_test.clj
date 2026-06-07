(ns reader.users-test
  "Integration tests for user lookup and provisioning against a real database."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.test-support.setup :refer [with-system]]
            [reader.users :as users]))

(deftest find-and-provision-test
  (with-system [system]
    (let [ds (:reader.db/datasource system)]

      (testing "no user yet for an unseen identity"
        (is (nil? (users/find-by-identity! ds {:hanko-id "h-1" :email "new@x.test"}))))

      (testing "create! provisions a row from identity attrs"
        (let [u (users/create! ds {:hanko-id "h-1" :email "new@x.test" :display-name "New"})]
          (is (= "new@x.test" (:users/email u)))
          (is (= "h-1" (:users/hanko-id u)))
          (is (= "New" (:users/display-name u)))))

      (testing "find-by-identity! finds an existing user by Hanko subject"
        (let [u (users/find-by-identity! ds {:hanko-id "h-1" :email "ignored@x.test"})]
          (is (= "new@x.test" (:users/email u)) "matched on subject, not email")))

      (testing "find-by-identity! reconciles a returning email to a new subject"
        (let [u     (users/create! ds {:hanko-id "old-sub" :email "ret@x.test"})
              found (users/find-by-identity! ds {:hanko-id "new-sub" :email "ret@x.test"})]
          (is (= (:users/id u) (:users/id found)) "same row, not a duplicate")
          (is (= "new-sub" (:users/hanko-id found)) "hanko_id backfilled to the current subject")))

      (testing "find-by-identity! never wipes a stored subject with a nil hanko-id"
        (let [u     (users/create! ds {:hanko-id "keep-sub" :email "keep@x.test"})
              found (users/find-by-identity! ds {:hanko-id nil :email "keep@x.test"})]
          (is (= (:users/id u) (:users/id found)) "matched the existing row by email")
          (is (= "keep-sub" (:users/hanko-id found)) "the stored subject is preserved, not nulled"))))))
