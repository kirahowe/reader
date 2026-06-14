(ns reader.storage.r2-test
  (:require [clojure.test :refer [deftest is testing]]
            [reader.storage :as storage]
            [reader.storage.r2 :as r2]))

;; The R2 GET/PUT path itself can only be exercised against a live bucket (done
;; at deploy). What we can check here is the fail-fast on missing credentials, so
;; a misconfigured prod refuses to boot rather than silently dropping mail.
(deftest store-requires-complete-credentials
  (let [complete {:account-id "acct" :bucket "b" :access-key "k" :secret "s"}]
    (testing "a complete config builds a Blobs store"
      (is (satisfies? storage/Blobs (r2/->store complete))))
    (testing "any missing or blank credential is a startup error"
      (doseq [k [:account-id :bucket :access-key :secret]]
        (is (thrown? clojure.lang.ExceptionInfo (r2/->store (assoc complete k "")))
            (str "blank " k " should fail fast"))
        (is (thrown? clojure.lang.ExceptionInfo (r2/->store (dissoc complete k)))
            (str "missing " k " should fail fast"))))))
