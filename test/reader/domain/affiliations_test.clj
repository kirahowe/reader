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
      ;; An institution is an author affiliation, not a source you read from.
      (crud/create! ds :affiliations {:name "MIT" :slug "mit" :type "institution"})

      (testing "lists only sources, ordered case-insensitively by name — institutions excluded"
        (is (= ["arXiv" "Astral Codex Ten" "The New Yorker"]
               (map :affiliations/name (affiliations/list-sorted ds))))))))

(deftest find-or-create!-test
  (with-system [system]
    (let [ds (:reader.db/datasource system)]
      (testing "idempotent by slug, with a conservative default type and the homepage url"
        (let [a (affiliations/find-or-create! ds "Stripe" "https://stripe.com")
              b (affiliations/find-or-create! ds "Stripe")]
          (is (= (:affiliations/id a) (:affiliations/id b)) "same name → one row")
          (is (= "other" (:affiliations/type a)))
          (is (= "https://stripe.com" (:affiliations/url a)) "url stored on first insert")
          (is (= "https://stripe.com" (:affiliations/url b)) "and kept, not cleared, on re-find"))))))

(deftest link-author!-test
  (with-system [system]
    (let [ds   (:reader.db/datasource system)
          ada  (crud/create! ds :authors {:name "Ada Lovelace" :slug "ada-link"})
          mit  (crud/create! ds :affiliations {:name "MIT" :slug "mit" :type "institution"})
          aid  (:authors/id ada)
          fid  (:affiliations/id mit)]
      (testing "creates the institutional-affiliation edge once and is idempotent on re-run"
        (affiliations/link-author! ds aid fid)
        (affiliations/link-author! ds aid fid)
        (let [links (crud/find-many ds :author-affiliations {:author-id aid :affiliation-id fid})]
          (is (= 1 (count links)) "no duplicate stint"))))))

(deftest by-slug-test
  (with-system [system]
    (let [ds (:reader.db/datasource system)]
      (crud/create! ds :affiliations {:name "The New Yorker" :slug "tny" :type "magazine"})
      (testing "returns the affiliation matching the slug, nil otherwise"
        (is (= "The New Yorker" (:affiliations/name (affiliations/by-slug ds "tny"))))
        (is (nil? (affiliations/by-slug ds "nope")))))))

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
