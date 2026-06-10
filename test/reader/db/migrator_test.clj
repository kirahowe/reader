(ns reader.db.migrator-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]
            [reader.db.migrator :as migrator]
            [reader.test-support.setup :refer [with-system]]))

(def v1-tables
  #{"authors" "affiliations" "author_affiliations" "newsletter_sources"
    "articles" "papers" "newsletter_issues" "authorships"
    "users" "email_inboxes" "queue_items" "jobs"})

(defn- public-tables [ds]
  (->> (jdbc/execute! ds ["SELECT table_name FROM information_schema.tables
                            WHERE table_schema = 'public'"])
       (map :tables/table_name)
       set))

(deftest migrator-creates-v1-schema
  (with-system [system]
    (let [tables (public-tables (:reader.db/datasource system))]
      (testing "every v1 table is present after the migrator runs"
        (is (set/subset? v1-tables tables)
            (str "missing tables: " (set/difference v1-tables tables)))))))

(deftest migrator-down-reverses-the-v1-schema
  (with-system [system]
    (let [ds     (:reader.db/datasource system)
          config (#'migrator/migratus-config ds "migrations")]
      (testing "rolling back every migration drops the v1 schema"
        ;; Roll back all applied migrations (not just the last) so this stays
        ;; correct as migrations accrue beyond the init one.
        (dotimes [_ (count (migratus/completed-list config))]
          (migratus/rollback config))
        (let [remaining (set/intersection v1-tables (public-tables ds))]
          (is (empty? remaining)
              (str "tables still present after rollback: " remaining)))))))
