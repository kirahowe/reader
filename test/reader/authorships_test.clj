(ns reader.authorships-test
  "Integration tests for `reader.authorships` — the polymorphic
   readable<->author bridge whose FK postgres cannot enforce."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.authorships :as authorships]
            [reader.db.crud :as crud]
            [reader.test-support.setup :refer [with-system]]))

(defn- seed [ds]
  (let [author  (crud/create! ds :authors      {:name "A" :sort-name "A" :slug "auth-a"})
        aff     (crud/create! ds :affiliations {:name "Aff" :slug "aff-a" :type "blog"})
        article (crud/create! ds :articles
                              {:affiliation-id (:affiliations/id aff)
                               :title          "T" :slug "t"
                               :canonical-url  "https://example.test/t"})
        paper   (crud/create! ds :papers
                              {:affiliation-id (:affiliations/id aff)
                               :title          "P" :doi "10.1000/p"
                               :pdf-object-key "papers/p.pdf"})
        issue   (crud/create! ds :newsletter-issues
                              {:affiliation-id       (:affiliations/id aff)
                               :subject              "S" :body-html "<p>S</p>"
                               :raw-email-object-key "issues/s.eml"})]
    {:author author :affiliation aff :article article :paper paper :issue issue}))

(deftest attach!-test
  (with-system [system]
    (let [ds                       (:reader.db/datasource system)
          {:keys [author article]} (seed ds)]

      (testing "creates an authorship row when the readable exists"
        (let [row (authorships/attach! ds {:author-id     (:authors/id author)
                                           :readable-type :article
                                           :readable-id   (:articles/id article)
                                           :ordinal       0})]
          (is (uuid? (:authorships/id row)))
          (is (= "article" (:authorships/readable-type row)))
          (is (= (:articles/id article) (:authorships/readable-id row)))
          (is (= (:authors/id author)   (:authorships/author-id row)))))

      (testing "throws when readable-type is not one of the known kinds"
        (is (thrown? clojure.lang.ExceptionInfo
                     (authorships/attach! ds {:author-id     (:authors/id author)
                                              :readable-type :tweet
                                              :readable-id   (:articles/id article)}))))

      (testing "throws when readable-id does not exist in the target table"
        (is (thrown? clojure.lang.ExceptionInfo
                     (authorships/attach! ds {:author-id     (:authors/id author)
                                              :readable-type :paper
                                              :readable-id   #uuid "00000000-0000-0000-0000-000000000000"})))))))

(deftest attach!-across-readable-kinds-test
  (with-system [system]
    (let [ds (:reader.db/datasource system)
          {:keys [author article paper issue]} (seed ds)]
      (doseq [[kind readable-id type-str]
              [[:article          (:articles/id article)        "article"]
               [:paper            (:papers/id paper)            "paper"]
               [:newsletter-issue (:newsletter-issues/id issue) "newsletter_issue"]]]
        (testing (str kind " attaches and stores the canonical readable_type")
          (let [row (authorships/attach! ds {:author-id     (:authors/id author)
                                             :readable-type kind
                                             :readable-id   readable-id})]
            (is (= type-str    (:authorships/readable-type row)))
            (is (= readable-id (:authorships/readable-id row)))
            (is (= 0 (:authorships/ordinal row)) "ordinal defaults to 0")
            (is (nil? (:authorships/contribution-type row))
                "an omitted contribution-type stays NULL")))))))

(deftest attach!-honors-optional-fields-test
  (with-system [system]
    (let [ds (:reader.db/datasource system)
          {:keys [author article]} (seed ds)
          row (authorships/attach! ds {:author-id         (:authors/id author)
                                       :readable-type     :article
                                       :readable-id       (:articles/id article)
                                       :ordinal           3
                                       :contribution-type "editor"})]
      (testing "explicit ordinal and contribution-type are persisted"
        (is (= 3        (:authorships/ordinal row)))
        (is (= "editor" (:authorships/contribution-type row)))))))
