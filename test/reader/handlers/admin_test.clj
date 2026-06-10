(ns reader.handlers.admin-test
  "The admin gate. The handler is built through integrant (so its namespace is
   loaded without a side-effecting require) with an explicit admin-emails set."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [reader.test-support.setup :refer [with-system]]))

(deftest admin-gate-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          cfg {:reader.handlers.admin/extractions {:datasource ds :admin-emails #{"admin@x.test"}}}]
      (ig/load-namespaces cfg)
      (let [sys     (ig/init cfg)
            handler (:reader.handlers.admin/extractions sys)]
        (try
          (testing "an admin sees the dashboard"
            (let [resp (handler {:user {:users/email "admin@x.test"}})]
              (is (= 200 (:status resp)))
              (is (str/includes? (:body resp) "Extraction eval"))))
          (testing "the gate is case-insensitive on the email"
            (is (= 200 (:status (handler {:user {:users/email "Admin@X.test"}})))))
          (testing "a non-admin gets 404 — no existence leak"
            (is (= 404 (:status (handler {:user {:users/email "nobody@x.test"}})))))
          (finally (ig/halt! sys)))))))
