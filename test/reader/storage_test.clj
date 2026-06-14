(ns reader.storage-test
  (:require [clojure.test :refer [deftest is testing]]
            [reader.storage :as storage])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- bytes->str [^bytes b] (when b (String. b "UTF-8")))

(defn- temp-dir []
  (.toString (Files/createTempDirectory "reader-blobs-test" (make-array FileAttribute 0))))

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

(deftest file-store-round-trips
  (let [store (storage/file-store (temp-dir))]
    (testing "an absent key reads as nil"
      (is (nil? (storage/get-object store "missing.eml"))))
    (testing "put then get returns the stored bytes, nested key path and all"
      (storage/put-object store "inbox/abc.eml" (.getBytes "raw email" "UTF-8") "message/rfc822")
      (is (= "raw email" (bytes->str (storage/get-object store "inbox/abc.eml")))))
    (testing "put overwrites an existing key"
      (storage/put-object store "k" (.getBytes "v1" "UTF-8") "text/plain")
      (storage/put-object store "k" (.getBytes "v2" "UTF-8") "text/plain")
      (is (= "v2" (bytes->str (storage/get-object store "k"))))))
  (testing "a blank root is rejected"
    (is (thrown? clojure.lang.ExceptionInfo (storage/file-store "")))))

(deftest disabled-store-throws-on-use
  (let [store (storage/disabled-store :test)]
    (is (satisfies? storage/Blobs store) "still a Blobs store, so boot succeeds")
    (is (thrown? clojure.lang.ExceptionInfo (storage/get-object store "k")))
    (is (thrown? clojure.lang.ExceptionInfo (storage/put-object store "k" (.getBytes "x" "UTF-8") "text/plain")))))

(deftest open-selects-backend
  (testing "the :memory backend opens a working Blobs store"
    (let [store (storage/open {:backend :memory})]
      (is (satisfies? storage/Blobs store))
      (storage/put-object store "k" (.getBytes "hi" "UTF-8") "text/plain")
      (is (= "hi" (bytes->str (storage/get-object store "k"))))))
  (testing "the :file backend opens a working Blobs store at the configured root"
    (let [store (storage/open {:backend :file :root (temp-dir)})]
      (storage/put-object store "k" (.getBytes "hi" "UTF-8") "text/plain")
      (is (= "hi" (bytes->str (storage/get-object store "k"))))))
  (testing "an unknown backend is a startup error, not a silent no-op"
    (is (thrown? clojure.lang.ExceptionInfo
                 (storage/open {:backend :nope})))))
