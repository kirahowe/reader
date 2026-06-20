(ns reader.handlers.settings-test
  "The settings page handlers, built through integrant against a real db."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [reader.db.crud :as crud]
            [reader.domain.inboxes :as inboxes]
            [reader.test-support.setup :refer [with-system]]))

(defn- mk-user [ds email] (:users/id (crud/create! ds :users {:email email})))

(defn- init-handler
  "Build a single settings handler `k` against `ds` (with `extra` config merged in),
   returning [system handler]. The caller ig/halt!s the system."
  [ds k extra]
  (let [cfg {k (merge {:datasource ds :inbound-domain "inbox.reader.test"} extra)}]
    (ig/load-namespaces cfg)
    (let [sys (ig/init cfg)]
      [sys (k sys)])))

(deftest settings-shows-inbound-alias-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          uid (mk-user ds "me@x.test")
          [sys handler] (init-handler ds :reader.handlers.settings/show {:active? false})]
      (try
        (let [resp (handler {:user {:users/email "me@x.test"} :user-id uid})
              body (:body resp)]
          (testing "renders the page with the signed-in identity"
            (is (= 200 (:status resp)))
            (is (str/includes? body "me@x.test")))
          (testing "provisions and shows the user's inbound alias at the configured domain"
            (is (re-find #"[a-z]+-[a-z]+-[a-z0-9]{4}@inbox\.reader\.test" body))
            (let [stored (:email-inboxes/alias (crud/find-1 ds :email-inboxes {:user-id uid}))]
              (is (str/includes? body stored) "the rendered alias is the one persisted")))
          (testing "flags the alias as not yet active when inbound isn't wired"
            (is (str/includes? body "isn’t receiving mail yet"))))
        (finally (ig/halt! sys))))))

(deftest settings-hides-pending-note-when-active-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          uid (mk-user ds "live@x.test")
          [sys handler] (init-handler ds :reader.handlers.settings/show {:active? true})]
      (try
        (let [body (:body (handler {:user {:users/email "live@x.test"} :user-id uid}))]
          (testing "no 'not active' note once inbound email is wired"
            (is (not (str/includes? body "isn’t receiving mail yet")))
            (is (re-find #"@inbox\.reader\.test" body) "still shows the alias")))
        (finally (ig/halt! sys))))))

(deftest rotate-requires-matching-confirmation-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          uid (mk-user ds "rot@x.test")
          old (:email-inboxes/alias (inboxes/find-or-provision! ds uid "inbox.reader.test"))
          [sys handler] (init-handler ds :reader.handlers.settings/rotate {})]
      (try
        (testing "a non-matching confirmation changes nothing and re-renders with an error (422)"
          (let [resp (handler {:user {:users/email "rot@x.test"} :user-id uid
                               :params {"confirm" "not-it@inbox.reader.test"}})]
            (is (= 422 (:status resp)))
            (is (str/includes? (:body resp) "didn’t match"))
            (is (str/includes? (:body resp) "not-it@inbox.reader.test")
                "echoes the typed value back so the user can correct it")
            (is (= old (:email-inboxes/alias (inboxes/current ds uid))) "alias untouched")))
        (testing "retyping the current address rotates it and redirects (303)"
          (let [resp (handler {:user {:users/email "rot@x.test"} :user-id uid
                               :params {"confirm" old}})]
            (is (= 303 (:status resp)))
            (is (= "/settings" (get-in resp [:headers "location"])))
            (let [now (:email-inboxes/alias (inboxes/current ds uid))]
              (is (not= old now) "a fresh alias was minted")
              (is (nil? (inboxes/by-alias ds old)) "the old alias no longer resolves"))))
        (finally (ig/halt! sys))))))

(deftest rotate-without-an-alias-provisions-and-errors-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          uid (mk-user ds "fresh@x.test")
          [sys handler] (init-handler ds :reader.handlers.settings/rotate {})]
      (try
        (testing "a user with no alias yet can't match the confirm, so the page provisions one and reports the mismatch (422)"
          (is (nil? (inboxes/current ds uid)) "no alias to start")
          (let [resp (handler {:user {:users/email "fresh@x.test"} :user-id uid
                               :params {"confirm" "anything@inbox.reader.test"}})
                now  (inboxes/current ds uid)]
            (is (= 422 (:status resp)))
            (is (str/includes? (:body resp) "didn’t match"))
            (is (some? now) "an alias was provisioned so the page has one to show")
            (is (str/includes? (:body resp) (:email-inboxes/alias now))
                "renders the freshly provisioned alias")))
        (finally (ig/halt! sys))))))
