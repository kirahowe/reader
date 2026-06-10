(ns reader.handlers.settings-test
  "The settings page handler, built through integrant against a real db."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [reader.db.crud :as crud]
            [reader.test-support.setup :refer [with-system]]))

(deftest settings-shows-inbound-alias-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          uid (:users/id (crud/create! ds :users {:email "me@x.test"}))
          cfg {:reader.handlers.settings/show {:datasource ds :inbound-domain "inbox.reader.test"}}]
      (ig/load-namespaces cfg)
      (let [sys     (ig/init cfg)
            handler (:reader.handlers.settings/show sys)]
        (try
          (let [resp (handler {:user {:users/email "me@x.test"} :user-id uid})
                body (:body resp)]
            (testing "renders the page with the signed-in identity"
              (is (= 200 (:status resp)))
              (is (str/includes? body "me@x.test")))
            (testing "provisions and shows the user's inbound alias at the configured domain"
              (is (re-find #"r-[0-9a-f]{32}@inbox\.reader\.test" body))
              (let [stored (:email-inboxes/alias (crud/find-1 ds :email-inboxes {:user-id uid}))]
                (is (str/includes? body stored) "the rendered alias is the one persisted")))
            (testing "flags the alias as not yet active"
              (is (str/includes? body "isn’t receiving mail yet"))))
          (finally (ig/halt! sys)))))))
