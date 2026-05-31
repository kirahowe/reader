(ns reader.dev.infra.postgres-test
  "Verifies the embedded-postgres Integrant component starts a real
   Postgres, exposes a usable connection spec, and tears down cleanly."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [reader.dev.infra.postgres]))

(deftest postgres-component-lifecycle
  (let [system (ig/init {:reader.dev.infra/postgres {}})]
    (try
      (let [spec (:reader.dev.infra/postgres system)]
        (testing "init returns a connection spec with the expected keys"
          (is (string? (:jdbc-url spec)))
          (is (re-matches #"jdbc:postgresql://.*" (:jdbc-url spec)))
          (is (string? (:username spec)))
          (is (string? (:password spec))))

        (testing "the spec can be used to open a working connection"
          ;; next.jdbc's raw map spec wants camelCase :jdbcUrl
          (let [conn-spec {:jdbcUrl  (:jdbc-url spec)
                           :user     (:username spec)
                           :password (:password spec)}
                row      (jdbc/execute-one! conn-spec ["SELECT 1 AS n"])]
            (is (= 1 (:n row))))))
      (finally
        (ig/halt! system)))))
