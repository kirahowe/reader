(ns reader.web.signature-test
  (:require [clojure.test :refer [deftest is testing]]
            [reader.web.signature :as sig]))

(deftest sign-and-verify
  (let [secret "s3cret" msg "1700000000.{\"a\":1}"]
    (testing "sign is deterministic lowercase hex"
      (is (= (sig/sign secret msg) (sig/sign secret msg)))
      (is (re-matches #"[0-9a-f]{64}" (sig/sign secret msg))))
    (testing "valid? accepts the matching signature"
      (is (sig/valid? secret msg (sig/sign secret msg))))
    (testing "valid? rejects a tampered message, wrong secret, or wrong signature"
      (is (not (sig/valid? secret (str msg "x") (sig/sign secret msg))))
      (is (not (sig/valid? "other" msg (sig/sign secret msg))))
      (is (not (sig/valid? secret msg "deadbeef"))))
    (testing "valid? fails closed on a blank secret or signature"
      (is (not (sig/valid? nil msg (sig/sign secret msg))))
      (is (not (sig/valid? "" msg (sig/sign secret msg))))
      (is (not (sig/valid? secret msg nil)))
      (is (not (sig/valid? secret msg ""))))))
