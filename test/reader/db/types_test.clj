(ns reader.db.types-test
  "Pure codec tests for `reader.db.types` — no datasource needed; the
   read-side protocol extension is exercised against raw PGobjects."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc.result-set :as rs]
            [reader.db.types :as types])
  (:import (org.postgresql.util PGobject)))

(defn- pgobject [type value]
  (doto (PGobject.) (.setType type) (.setValue value)))

(deftest reads-json-and-jsonb-into-clojure
  (testing "a jsonb PGobject decodes to Clojure data with keyword keys"
    (is (= {:a 1} (rs/read-column-by-index (pgobject "jsonb" "{\"a\":1}") nil nil))))

  (testing "a plain json PGobject decodes the same way"
    (is (= {:b 2} (rs/read-column-by-index (pgobject "json" "{\"b\":2}") nil nil))))

  (testing "a non-JSON PGobject passes through untouched"
    (let [obj (pgobject "inet" "127.0.0.1")]
      (is (identical? obj (rs/read-column-by-index obj nil nil))))))

(deftest jsonb-round-trips-through-the-codec
  (testing "->jsonb then read returns the original value"
    (let [v {:source "manual" :tags ["a" "b"] :n 3}]
      (is (= v (rs/read-column-by-index (types/->jsonb v) nil nil))))))
