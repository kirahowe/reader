(ns reader.domain.articles-test
  "Tests for `reader.domain.articles/ingest-attrs` — the pure map from an
   extraction context to article columns. Needs no database."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.domain.articles :as articles]))

(deftest ingest-attrs-test
  (let [now (java.time.Instant/parse "2026-06-07T00:00:00Z")
        pub (java.time.Instant/parse "2026-05-14T09:30:00Z")
        ctx {:url    "https://x.test/a"
             :fields {:title        {:value "A Real Title" :source :json-ld}
                      :lang         {:value "en" :source :html-lang}
                      :published-at {:value pub :source :json-ld}}
             :body   {:html "<p>hi</p>" :word-count 42 :reading-time-secs 11}}
        attrs (articles/ingest-attrs ctx #uuid "00000000-0000-0000-0000-0000000000a1" now)]
    (testing "maps extraction fields + body + injected timestamp to article columns"
      (is (= "A Real Title" (:title attrs)))
      (is (= "a-real-title" (:slug attrs)))
      (is (= "<p>hi</p>" (:body-html attrs)))
      (is (= 42 (:word-count attrs)))
      (is (= 11 (:reading-time-secs attrs)))
      (is (= "en" (:lang attrs)))
      (is (= pub (:published-at attrs)))
      (is (= now (:updated-at attrs)))
      (is (= #uuid "00000000-0000-0000-0000-0000000000a1" (:affiliation-id attrs))))
    (testing "title falls back to the url; absent optional fields are omitted"
      (let [bare (articles/ingest-attrs {:url "https://x.test/p" :fields {} :body {}} nil now)]
        (is (= "https://x.test/p" (:title bare)))
        (is (not (contains? bare :affiliation-id)))
        (is (not (contains? bare :lang)))
        (is (not (contains? bare :published-at)))))))
