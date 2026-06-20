(ns reader.util.url-test
  (:require [clojure.test :refer [deftest is testing]]
            [reader.util.url :as url]))

(deftest canonicalize-test
  (testing "lowercases scheme and host, preserving case-sensitive path and query"
    (is (= "https://example.com/Path?Q=1" (url/canonicalize "HTTPS://Example.COM/Path?Q=1"))))
  (testing "drops the default port but keeps a non-default one"
    (is (= "https://x.test/a" (url/canonicalize "https://x.test:443/a")))
    (is (= "http://x.test/a" (url/canonicalize "http://x.test:80/a")))
    (is (= "https://x.test:8443/a" (url/canonicalize "https://x.test:8443/a"))))
  (testing "strips the fragment and keeps the query"
    (is (= "https://x.test/a?id=2&b=3" (url/canonicalize "https://x.test/a?id=2&b=3#frag"))))
  (testing "an empty path normalizes to /"
    (is (= "https://x.test/" (url/canonicalize "https://x.test"))))
  (testing "trivially equivalent URLs collapse to one canonical key"
    (is (= (url/canonicalize "https://Example.com/p")
           (url/canonicalize "https://example.com/p#intro"))))
  (testing "non-http(s) or unparseable input is nil"
    (is (nil? (url/canonicalize "ftp://x.test/a")))
    (is (nil? (url/canonicalize "file:///etc/passwd")))
    (is (nil? (url/canonicalize "not a url")))
    (is (nil? (url/canonicalize "  ")))
    (is (nil? (url/canonicalize nil))))
  (testing "valid? mirrors canonicalize"
    (is (url/valid? "https://x.test/a"))
    (is (not (url/valid? "nope")))))
