(ns reader.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [reader.db :as db]
            [reader.test-support.setup :refer [with-system]]))

(deftest datasource-accepts-real-queries
  (with-system [system]
    (is (= 1 (:n (jdbc/execute-one! (:reader.db/datasource system)
                                    ["SELECT 1 AS n"]))))))

(deftest kebab->camel-test
  (let [kebab->camel #'db/kebab->camel]
    (testing "every prod datasource key maps to its HikariCP property"
      (is (= {:jdbcUrl           1
              :poolName          1
              :maximumPoolSize   1
              :minimumIdle       1
              :connectionTimeout 1
              :idleTimeout       1
              :maxLifetime       1}
             (update-keys {:jdbc-url           1
                           :pool-name          1
                           :maximum-pool-size  1
                           :minimum-idle       1
                           :connection-timeout 1
                           :idle-timeout       1
                           :max-lifetime       1}
                          kebab->camel))))
    (testing "a single-word key is unchanged"
      (is (= :username (kebab->camel :username))))))

(deftest redact-url-test
  (let [redact #'db/redact-url]
    (testing "strips embedded credentials from a Neon-style URL"
      (is (= "jdbc:postgresql://ep-x.neon.tech/reader?sslmode=require"
             (redact "jdbc:postgresql://user:pass@ep-x.neon.tech/reader?sslmode=require"))))
    (testing "credential-free dev URL passes through unchanged"
      (is (= "jdbc:postgresql://localhost:5432/postgres"
             (redact "jdbc:postgresql://localhost:5432/postgres"))))
    (testing "nil is tolerated"
      (is (nil? (redact nil))))))
