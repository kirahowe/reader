(ns reader.concerns.integrant-test
  "Unit tests for the EDN reader literals — here the #env/set parser, which
   loads the prod invite allowlist (a security control) from a comma-separated
   env var."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.concerns.integrant :as integrant]))

(def ^:private csv->set #'integrant/csv->set)

(deftest csv->set-test
  (testing "splits on commas, trimming surrounding whitespace"
    (is (= #{"a@x.test" "b@x.test"} (csv->set "a@x.test, b@x.test"))))

  (testing "drops blank and empty entries"
    (is (= #{"a@x.test" "b@x.test"} (csv->set " a@x.test ,, b@x.test , "))))

  (testing "nil or blank input yields the empty set"
    (is (= #{} (csv->set nil)))
    (is (= #{} (csv->set "")))
    (is (= #{} (csv->set "   ")))))
