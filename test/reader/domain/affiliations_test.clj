(ns reader.domain.affiliations-test
  "Tests for `reader.domain.affiliations`."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.domain.affiliations :as affiliations]
            [reader.db.crud :as crud]
            [reader.test-support.setup :refer [with-system]]))

(deftest list-sorted-test
  (with-system [system]
    (let [ds (:reader.db/datasource system)]
      ;; Mixed case so the ordering proves it is case-insensitive, not the
      ;; default byte-collation that would sort "arXiv" after "The New Yorker".
      (crud/create! ds :affiliations {:name "The New Yorker" :slug "tny"   :type "magazine"})
      (crud/create! ds :affiliations {:name "arXiv"          :slug "arxiv" :type "preprint"})
      (crud/create! ds :affiliations {:name "Astral Codex Ten" :slug "act" :type "newsletter"})

      (testing "ordered case-insensitively by name"
        (is (= ["arXiv" "Astral Codex Ten" "The New Yorker"]
               (map :affiliations/name (affiliations/list-sorted ds))))))))

(deftest resolve-canonicalizes-test
  (with-system [system]
    (let [ds (:reader.db/datasource system)]
      (testing "creates an institution and dedups by ROR even when the name differs"
        (let [a (affiliations/resolve! ds {:name "Massachusetts Institute of Technology"
                                           :type "institution" :ror "https://ror.org/042nb2s44"})
              b (affiliations/resolve! ds {:name "MIT" :type "institution"
                                           :ror "https://ror.org/042nb2s44"})]
          (is (= (:affiliations/id a) (:affiliations/id b)) "same ROR → one institution")
          (is (= "institution" (:affiliations/type a)))))

      (testing "a different ROR is a distinct row with a disambiguated slug"
        (let [a (affiliations/resolve! ds {:name "Institute" :ror "https://ror.org/aaaa"})
              b (affiliations/resolve! ds {:name "Institute" :ror "https://ror.org/bbbb"})]
          (is (not= (:affiliations/id a) (:affiliations/id b)))
          (is (not= (:affiliations/slug a) (:affiliations/slug b))))))))
