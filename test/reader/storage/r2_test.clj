(ns reader.storage.r2-test
  (:require [clojure.test :refer [deftest is testing]]
            [reader.storage.r2 :as r2]))

(def ^:private empty-sha256
  "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

(deftest sigv4-matches-aws-published-vector
  ;; AWS's documented GET Object example (Signature Version 4 test suite). If our
  ;; signer reproduces the published signature byte-for-byte, the canonical
  ;; request, string-to-sign, and signing-key derivation are all correct — which
  ;; is the only part of the R2 client that can't be checked without live creds.
  (testing "the GET Object example signature"
    (let [{:keys [signature signed-headers]}
          (r2/sigv4-signature
           {:method        "GET"
            :canonical-uri "/test.txt"
            :query         ""
            :headers       {"host"                 "examplebucket.s3.amazonaws.com"
                            "range"                "bytes=0-9"
                            "x-amz-content-sha256" empty-sha256
                            "x-amz-date"           "20130524T000000Z"}
            :payload-hash  empty-sha256
            :region        "us-east-1"
            :service       "s3"
            :access-key    "AKIAIOSFODNN7EXAMPLE"
            :secret        "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
            :amzdate       "20130524T000000Z"
            :datestamp     "20130524"})]
      (is (= "f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41" signature))
      (is (= "host;range;x-amz-content-sha256;x-amz-date" signed-headers)))))
