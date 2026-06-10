(ns reader.inboxes-test
  "Per-user inbound alias provisioning. Real embedded Postgres via with-system."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.db.crud :as crud]
            [reader.inboxes :as inboxes]
            [reader.test-support.setup :refer [with-system]]))

(defn- mk-user [ds email] (:users/id (crud/create! ds :users {:email email})))

(deftest find-or-provision-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          uid (mk-user ds "nl@x.test")]
      (testing "provisions one alias at the configured domain on first call"
        (let [inbox (inboxes/find-or-provision! ds uid "inbox.reader.test")
              addr  (:email-inboxes/alias inbox)]
          (is (re-matches #"r-[0-9a-f]{32}@inbox\.reader\.test" addr)
              "an unguessable token at the configured domain")
          (is (= uid (:email-inboxes/user-id inbox)))))

      (testing "is idempotent — a second call returns the same alias, no duplicate row"
        (let [a1 (:email-inboxes/alias (inboxes/find-or-provision! ds uid "inbox.reader.test"))
              a2 (:email-inboxes/alias (inboxes/find-or-provision! ds uid "inbox.reader.test"))]
          (is (= a1 a2))
          (is (= 1 (count (crud/find-many ds :email-inboxes {:user-id uid}))))))

      (testing "different users get different aliases"
        (let [other (mk-user ds "other@x.test")
              a1    (:email-inboxes/alias (inboxes/find-or-provision! ds uid "inbox.reader.test"))
              a2    (:email-inboxes/alias (inboxes/find-or-provision! ds other "inbox.reader.test"))]
          (is (not= a1 a2)))))))
