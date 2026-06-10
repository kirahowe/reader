(ns reader.articles-test
  "Tests for `reader.articles`. The pure form helpers (slugify, parse-form,
   validate) need no database; `create!` is integration-tested separately."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.articles :as articles]
            [reader.db.crud :as crud]
            [reader.test-support.setup :refer [with-system]]))

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

(deftest parse-form-test
  (testing "trims required fields"
    (is (= {:title "Hi" :canonical-url "https://x.test/a"}
           (articles/parse-form {"title" "  Hi " "canonical-url" " https://x.test/a "}))))
  (testing "optional fields: blank dropped, present trimmed and kept"
    (is (= {:title "Hi" :canonical-url "https://x.test/a" :abstract "An abstract"}
           (articles/parse-form {"title"          "Hi"
                                 "canonical-url"  "https://x.test/a"
                                 "abstract"       " An abstract "
                                 "affiliation-id" "   "})))
    (is (= {:title "Hi" :canonical-url "https://x.test/a"
            :affiliation-id "00000000-0000-0000-0000-0000000000a1"}
           (articles/parse-form {"title"          "Hi"
                                 "canonical-url"  "https://x.test/a"
                                 "affiliation-id" "00000000-0000-0000-0000-0000000000a1"}))))
  (testing "missing required fields parse to nil (not dropped)"
    (is (= {:title nil :canonical-url nil} (articles/parse-form {})))))

(deftest validate-test
  (testing "well-formed input has no errors"
    (is (nil? (articles/validate {:title "Hi" :canonical-url "https://x.test/a"})))
    (is (nil? (articles/validate {:title          "Hi"
                                  :canonical-url  "http://x.test"
                                  :abstract       "a"
                                  :affiliation-id "00000000-0000-0000-0000-0000000000a1"}))))
  (testing "a missing title is reported with human copy"
    (is (= ["Title is required."]
           (:title (articles/validate {:title nil :canonical-url "https://x.test"})))))
  (testing "a non-http(s) url is reported with human copy"
    (is (= ["Enter a full URL starting with http:// or https://"]
           (:canonical-url (articles/validate {:title "Hi" :canonical-url "notaurl"})))))
  (testing "a malformed affiliation-id is reported"
    (is (= ["Unknown source."]
           (:affiliation-id (articles/validate {:title          "Hi"
                                                :canonical-url  "https://x.test"
                                                :affiliation-id "nope"}))))))

(deftest create!-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          aff (crud/create! ds :affiliations {:name "The New Yorker" :slug "tny" :type "magazine"})]

      (testing "valid input inserts an article with a derived slug"
        (let [{:keys [article errors]}
              (articles/create! ds {"title"          "A Fresh Take"
                                    "canonical-url"  "https://example.com/fresh"
                                    "affiliation-id" (str (:affiliations/id aff))})]
          (is (nil? errors))
          (is (= "A Fresh Take" (:articles/title article)))
          (is (= "a-fresh-take" (:articles/slug article)) "slug derived from the title")
          (is (= (:affiliations/id aff) (:articles/affiliation-id article))
              "affiliation-id is parsed to a uuid and stored as the FK")
          (testing "and the row is actually persisted"
            (is (= (:articles/id article)
                   (:articles/id (crud/find-1 ds :articles {:canonical-url "https://example.com/fresh"})))))))

      (testing "a duplicate canonical-url is reported, not thrown"
        (let [{:keys [article errors]}
              (articles/create! ds {"title"         "Same URL"
                                    "canonical-url" "https://example.com/fresh"})]
          (is (nil? article))
          (is (contains? errors :canonical-url))))

      (testing "a well-formed but non-existent affiliation-id is reported, not thrown"
        ;; Passes the Malli uuid check but has no matching row, so the insert
        ;; trips a foreign-key violation (23503). That must surface as a form
        ;; error, the same as the duplicate-url case — never a thrown 500.
        (let [{:keys [article errors]}
              (articles/create! ds {"title"          "Orphaned"
                                    "canonical-url"  "https://example.com/orphan"
                                    "affiliation-id" "00000000-0000-0000-0000-0000000000ff"})]
          (is (nil? article))
          (is (= ["Unknown source."] (:affiliation-id errors)))
          (is (nil? (crud/find-1 ds :articles {:canonical-url "https://example.com/orphan"}))
              "nothing is inserted when the affiliation doesn't exist")))

      (testing "invalid input is reported and writes nothing"
        (let [{:keys [article errors]}
              (articles/create! ds {"title"         "  "
                                    "canonical-url" "https://example.com/blank"})]
          (is (nil? article))
          (is (contains? errors :title))
          (is (nil? (crud/find-1 ds :articles {:canonical-url "https://example.com/blank"}))
              "nothing is inserted for an invalid submission"))))))
