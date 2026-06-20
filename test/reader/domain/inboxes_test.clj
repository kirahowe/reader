(ns reader.domain.inboxes-test
  "Per-user inbound alias provisioning. Real embedded Postgres via with-system."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.db.crud :as crud]
            [reader.domain.inboxes :as inboxes]
            [reader.test-support.setup :refer [with-system]]))

(defn- mk-user [ds email] (:users/id (crud/create! ds :users {:email email})))

(deftest find-or-provision-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          uid (mk-user ds "nl@x.test")]
      (testing "provisions one word-based alias at the configured domain on first call"
        (let [inbox (inboxes/find-or-provision! ds uid "inbox.reader.test")
              addr  (:email-inboxes/alias inbox)]
          (is (re-matches #"[a-z]+-[a-z]+-[a-z0-9]{4}@inbox\.reader\.test" addr)
              "a friendly adjective-noun name plus an unguessable token, at the configured domain")
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

(deftest current-returns-the-users-inbox
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          uid (mk-user ds "cur@x.test")]
      (testing "nil before provisioning, the displayed row after"
        (is (nil? (inboxes/current ds uid)))
        (let [inbox (inboxes/find-or-provision! ds uid "inbox.reader.test")]
          (is (= (:email-inboxes/alias inbox)
                 (:email-inboxes/alias (inboxes/current ds uid)))))))))

(deftest rotate-replaces-the-alias
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          uid (mk-user ds "rot@x.test")
          old (:email-inboxes/alias (inboxes/find-or-provision! ds uid "inbox.reader.test"))
          new (inboxes/rotate! ds uid "inbox.reader.test")]
      (testing "mints a fresh, different alias owned by the same user"
        (is (not= old (:email-inboxes/alias new)))
        (is (= uid (:email-inboxes/user-id new)))
        (is (re-matches #"[a-z]+-[a-z]+-[a-z0-9]{4}@inbox\.reader\.test"
                        (:email-inboxes/alias new))))
      (testing "the old alias no longer resolves — mail to it is undeliverable"
        (is (nil? (inboxes/by-alias ds old))))
      (testing "the new alias resolves to the same user"
        (is (= uid (:email-inboxes/user-id (inboxes/by-alias ds (:email-inboxes/alias new))))))
      (testing "exactly one inbox row remains"
        (is (= 1 (count (crud/find-many ds :email-inboxes {:user-id uid}))))))))

(deftest rotate-leaves-other-users-untouched
  (with-system [system]
    (let [ds       (:reader.db/datasource system)
          u1       (mk-user ds "keep@x.test")
          u2       (mk-user ds "churn@x.test")
          u1-alias (:email-inboxes/alias (inboxes/find-or-provision! ds u1 "inbox.reader.test"))]
      (inboxes/find-or-provision! ds u2 "inbox.reader.test")
      (inboxes/rotate! ds u2 "inbox.reader.test")
      (testing "a peer's rotation doesn't disturb this user's alias"
        (is (= u1 (:email-inboxes/user-id (inboxes/by-alias ds u1-alias))))
        (is (= 1 (count (crud/find-many ds :email-inboxes {:user-id u1}))))))))

(deftest provision-retries-on-alias-collision
  (with-system [system]
    (let [ds    (:reader.db/datasource system)
          u1    (mk-user ds "first@x.test")
          u2    (mk-user ds "second@x.test")
          taken (:email-inboxes/alias (inboxes/find-or-provision! ds u1 "inbox.reader.test"))
          calls (atom 0)]
      (testing "a generated alias that collides on the unique index is regenerated, not thrown"
        ;; Force the first generated name to duplicate u1's, then return a fresh
        ;; one — provisioning must recover via the unique index and retry.
        (with-redefs [inboxes/gen-alias (fn [_domain]
                                          (if (zero? @calls)
                                            (do (swap! calls inc) taken)
                                            "fresh-unique-aa11bb@inbox.reader.test"))]
          (let [row (inboxes/find-or-provision! ds u2 "inbox.reader.test")]
            (is (= "fresh-unique-aa11bb@inbox.reader.test" (:email-inboxes/alias row))
                "retried past the duplicate to a fresh alias")
            (is (= 1 @calls) "the colliding alias was attempted first")
            (is (= u2 (:email-inboxes/user-id row)))))))))
