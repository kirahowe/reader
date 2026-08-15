(ns reader.reprocess-newsletters-test
  (:require [clojure.test :refer [deftest is testing]]
            [reader.reprocess-newsletters :as reprocess]))

(deftest parse-limit-test
  (testing "defaults and accepts bounded explicit batches"
    (is (= 100 (reprocess/parse-limit nil)))
    (is (= 1 (reprocess/parse-limit "1")))
    (is (= 1000 (reprocess/parse-limit "1000"))))
  (testing "rejects malformed, empty, zero, negative, and oversized batches"
    (doseq [value ["" "many" "0" "-1" "1001"]]
      (is (thrown? clojure.lang.ExceptionInfo (reprocess/parse-limit value))))))
