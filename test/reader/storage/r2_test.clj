(ns reader.storage.r2-test
  (:require [clojure.test :refer [deftest is testing]]
            [reader.storage :as storage]
            [reader.storage.r2 :as r2]))

;; The R2 GET/PUT path itself can only be exercised against a live bucket (done
;; at deploy). What we can check here is the wiring: a complete config builds a
;; usable store, and an incomplete one degrades to a disabled store (boots, but
;; throws on use) rather than failing startup — inbound email is optional.
(deftest store-builds-or-degrades
  (let [complete {:account-id "acct" :bucket "b" :access-key "k" :secret "s"}]
    (testing "a complete config builds a Blobs store"
      (is (satisfies? storage/Blobs (r2/->store complete))))
    (testing "any missing or blank credential degrades to a disabled (throws-on-use) store"
      (doseq [k [:account-id :bucket :access-key :secret]]
        (doseq [cfg [(assoc complete k "") (dissoc complete k)]]
          (let [store (r2/->store cfg)]
            (is (satisfies? storage/Blobs store) (str "still boots with " k " absent"))
            (is (thrown? clojure.lang.ExceptionInfo (storage/get-object store "x"))
                (str "but throws on use with " k " absent"))))))))
