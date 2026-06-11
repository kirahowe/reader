(ns reader.db.migrator-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]
            [reader.db.migrator :as migrator]
            [reader.main :as main]
            [reader.test-support.setup :as setup :refer [with-system]]))

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

(deftest migrate-on-init-gates-the-on-boot-migration
  ;; Bring up a fresh embedded datasource WITHOUT the migrator (init only the
  ;; datasource key) so the schema starts empty, then drive the migrator's
  ;; init-key directly to prove the gate controls whether migrations apply.
  ;; This is the behaviour prod relies on: it migrates out-of-band via the Fly
  ;; release_command and boots the app with :migrate-on-init? false.
  (let [config (main/prep-config setup/test-profiles)
        system (ig/init config [:reader.db/datasource])
        ds     (:reader.db/datasource system)]
    (try
      (testing "gate off: skips migration and returns the datasource untouched"
        (let [returned (ig/init-key :reader.db/migrator
                                    {:datasource       ds
                                     :migrations-path  "migrations"
                                     :migrate-on-init? false})]
          (is (identical? ds returned))
          (is (empty? (set/intersection v1-tables (public-tables ds)))
              "no v1 tables should exist when the on-boot migrate is skipped")))
      (testing "gate on (the default): applies migrations against the datasource"
        (is (identical? ds (ig/init-key :reader.db/migrator
                                        {:datasource      ds
                                         :migrations-path "migrations"})))
        (is (set/subset? v1-tables (public-tables ds))
            "every v1 table is present once the on-boot migrate runs"))
      (finally (ig/halt! system)))))

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
