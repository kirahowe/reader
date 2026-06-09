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

(deftest normalize-spec-test
  (let [normalize #'db/normalize-spec]
    (testing "Neon libpq URL: lifts embedded credentials out, drops channel_binding, keeps sslmode"
      (is (= {:jdbc-url  "jdbc:postgresql://ep-x-pooler.neon.tech/neondb?sslmode=require"
              :pool-name "reader-pool"
              :username  "neondb_owner"
              :password  "npg_secret"}
             (normalize {:jdbc-url  "postgresql://neondb_owner:npg_secret@ep-x-pooler.neon.tech/neondb?sslmode=require&channel_binding=require"
                         :pool-name "reader-pool"}))))
    (testing "a jdbc:-prefixed URL with embedded credentials is handled too"
      (is (= {:jdbc-url "jdbc:postgresql://ep-x.neon.tech/neondb?sslmode=require"
              :username "u"
              :password "p"}
             (normalize {:jdbc-url "jdbc:postgresql://u:p@ep-x.neon.tech/neondb?sslmode=require"}))))
    (testing "when channel_binding is the only query param, no dangling ?"
      (is (= {:jdbc-url "jdbc:postgresql://ep-x.neon.tech/neondb"
              :username "u"
              :password "p"}
             (normalize {:jdbc-url "postgresql://u:p@ep-x.neon.tech/neondb?channel_binding=require"}))))
    (testing "the bare postgres:// scheme alias is normalized to jdbc:postgresql://"
      (is (= {:jdbc-url "jdbc:postgresql://ep-x.neon.tech/neondb?sslmode=require"
              :username "u"
              :password "p"}
             (normalize {:jdbc-url "postgres://u:p@ep-x.neon.tech/neondb?sslmode=require"}))))
    (testing "a password containing a colon is split only on the first colon"
      (is (= "pa:ss" (:password (normalize {:jdbc-url "postgresql://u:pa:ss@ep-x.neon.tech/db"})))))
    (testing "credential-free dev URL passes through with no username/password added"
      (is (= {:jdbc-url "jdbc:postgresql://localhost:5432/postgres"}
             (normalize {:jdbc-url "jdbc:postgresql://localhost:5432/postgres"}))))
    (testing "nil jdbc-url is tolerated"
      (is (= {:pool-name "x"} (normalize {:pool-name "x"}))))))

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
