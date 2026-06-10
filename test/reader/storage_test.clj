(ns reader.storage-test
  (:require [clojure.test :refer [deftest is testing]]
            [reader.storage :as storage]))

(defn- bytes->str [^bytes b] (when b (String. b "UTF-8")))

(deftest memory-store-round-trips
  (let [store (storage/memory-store)]
    (testing "an absent key reads as nil"
      (is (nil? (storage/get-object store "missing"))))
    (testing "put then get returns the stored bytes"
      (storage/put-object store "inbox/abc.eml" (.getBytes "raw email" "UTF-8") "message/rfc822")
      (is (= "raw email" (bytes->str (storage/get-object store "inbox/abc.eml")))))
    (testing "put overwrites an existing key"
      (storage/put-object store "k" (.getBytes "v1" "UTF-8") "text/plain")
      (storage/put-object store "k" (.getBytes "v2" "UTF-8") "text/plain")
      (is (= "v2" (bytes->str (storage/get-object store "k")))))))

(deftest open-selects-backend
  (testing "the :memory backend opens a working Blobs store"
    (let [store (storage/open {:backend :memory})]
      (is (satisfies? storage/Blobs store))
      (storage/put-object store "k" (.getBytes "hi" "UTF-8") "text/plain")
      (is (= "hi" (bytes->str (storage/get-object store "k"))))))
  (testing "an unknown backend is a startup error, not a silent no-op"
    (is (thrown? clojure.lang.ExceptionInfo
                 (storage/open {:backend :nope})))))
