(ns reader.domain.authors-test
  "Tests for `reader.domain.authors`: the pure sort-name heuristic plus the
   create!/list-sorted integration behaviour it feeds."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.domain.authors :as authors]
            [reader.db.crud :as crud]
            [reader.test-support.setup :refer [with-system]]))

(deftest derive-sort-name-test
  (testing "unambiguous First Last derives Last, First"
    (is (= "Didion, Joan"  (authors/derive-sort-name "Joan Didion")))
    (is (= "Smith, Zadie"  (authors/derive-sort-name "Zadie Smith")))
    (is (= "McPhee, John"  (authors/derive-sort-name "John McPhee"))))

  (testing "surrounding/irregular whitespace is tolerated"
    (is (= "Didion, Joan" (authors/derive-sort-name "  Joan   Didion "))))

  (testing "nil/blank input yields nil, never throws"
    (is (nil? (authors/derive-sort-name nil)))
    (is (nil? (authors/derive-sort-name "")))
    (is (nil? (authors/derive-sort-name "   "))))

  (testing "mononyms and orgs (token count != 2) are left alone"
    (is (nil? (authors/derive-sort-name "Plato")))
    (is (nil? (authors/derive-sort-name "OpenAI")))
    (is (nil? (authors/derive-sort-name "J. K. Rowling"))))

  (testing "lowercase styling is not surname-flipped"
    (is (nil? (authors/derive-sort-name "bell hooks")))
    (is (nil? (authors/derive-sort-name "Joan didion"))))

  (testing "leading articles are not treated as first names"
    (is (nil? (authors/derive-sort-name "The Atlantic")))
    (is (nil? (authors/derive-sort-name "The Economist"))))

  (testing "name particles make the surname ambiguous -> bail"
    (is (nil? (authors/derive-sort-name "Van Halen")))
    (is (nil? (authors/derive-sort-name "De Beauvoir"))))

  (testing "generational/credential suffixes -> bail"
    (is (nil? (authors/derive-sort-name "Smith Jr")))
    (is (nil? (authors/derive-sort-name "Sapolsky PhD")))))

(deftest create!-derives-sort-name-test
  (with-system [system]
    (let [ds (:reader.db/datasource system)]

      (testing "omitted sort-name is auto-derived for a clean First Last"
        (let [row (authors/create! ds {:name "Joan Didion" :slug "jd-derive"})]
          (is (= "Didion, Joan" (:authors/sort-name row)))))

      (testing "ambiguous name leaves sort-name NULL rather than guessing"
        (let [row (authors/create! ds {:name "bell hooks" :slug "bh-null"})]
          (is (nil? (:authors/sort-name row)))))

      (testing "an explicit sort-name always wins over the heuristic"
        (let [row (authors/create! ds {:name      "Joan Didion"
                                       :sort-name "Override, Manual"
                                       :slug      "jd-override"})]
          (is (= "Override, Manual" (:authors/sort-name row)))))

      (testing "an explicit nil sort-name is honored (not re-derived)"
        (let [row (authors/create! ds {:name "Joan Didion" :sort-name nil :slug "jd-nil"})]
          (is (nil? (:authors/sort-name row))))))))

(deftest list-sorted-test
  (with-system [system]
    (let [ds (:reader.db/datasource system)]
      ;; Mix of derived keys and a NULL (mononym) that falls back to `name`.
      ;; Leading letters chosen so the order holds under any collation.
      (authors/create! ds {:name "Zadie Smith" :slug "ls-smith"})       ; -> "Smith, Zadie"
      (authors/create! ds {:name "Joan Didion" :slug "ls-didion"})      ; -> "Didion, Joan"
      (authors/create! ds {:name "Aristotle"   :slug "ls-aristotle"})   ; NULL -> "Aristotle"
      (authors/create! ds {:name "John McPhee" :slug "ls-mcphee"})      ; -> "McPhee, John"

      (testing "ordered by COALESCE(sort_name, name)"
        ;; Aristotle < Didion, Joan < McPhee, John < Smith, Zadie
        (is (= ["Aristotle" "Joan Didion" "John McPhee" "Zadie Smith"]
               (map :authors/name (authors/list-sorted ds))))))))

(deftest by-slug-test
  (with-system [system]
    (let [ds (:reader.db/datasource system)]
      (authors/create! ds {:name "Joan Didion" :slug "joan-didion"})

      (testing "returns the author matching the slug"
        (is (= "Joan Didion" (:authors/name (authors/by-slug ds "joan-didion")))))

      (testing "returns nil for an unknown slug"
        (is (nil? (authors/by-slug ds "nobody")))))))

(deftest resolve-canonicalizes-test
  (with-system [system]
    (let [ds (:reader.db/datasource system)]
      (testing "matches an existing author by ORCID even when the display name differs"
        (let [a (authors/resolve! ds {:name "Yann LeCun" :orcid "0000-0001-1111-1111"})
              b (authors/resolve! ds {:name "Y. LeCun"   :orcid "0000-0001-1111-1111"})]
          (is (= (:authors/id a) (:authors/id b)) "same ORCID → one author")
          (is (= 1 (count (crud/find-many ds :authors {:orcid "0000-0001-1111-1111"}))))))

      (testing "matches by OpenAlex id when ORCID is absent"
        (let [a (authors/resolve! ds {:name "Jane Roe" :openalex-id "A123"})
              b (authors/resolve! ds {:name "J. Roe"   :openalex-id "A123"})]
          (is (= (:authors/id a) (:authors/id b)))))

      (testing "fills newly-known ids onto a name-only author without clobbering existing fields"
        (let [a (authors/resolve! ds {:name "Grace Hopper" :url "https://gh.example"})
              b (authors/resolve! ds {:name "Grace Hopper" :orcid "0000-0002-2222-2222"
                                      :url  "https://other.example"})]
          (is (= (:authors/id a) (:authors/id b)) "same name, no conflicting id → merged")
          (is (= "0000-0002-2222-2222" (:authors/orcid b)) "orcid filled in")
          (is (= "https://gh.example" (:authors/url b)) "existing url not clobbered")))

      (testing "distinct people sharing a name (different ORCIDs) stay separate, slugs disambiguated"
        (let [a (authors/resolve! ds {:name "John Smith" :orcid "0000-0003-3333-3333"})
              b (authors/resolve! ds {:name "John Smith" :orcid "0000-0004-4444-4444"})]
          (is (not= (:authors/id a) (:authors/id b)) "different ORCID → two authors")
          (is (not= (:authors/slug a) (:authors/slug b)) "slugs disambiguated"))))))

(deftest institutions-of-test
  (with-system [system]
    (let [ds       (:reader.db/datasource system)
          didion   (authors/create! ds {:name "Joan Didion" :slug "jd"})
          smith    (authors/create! ds {:name "Zadie Smith" :slug "zs"})
          stanford (crud/create! ds :affiliations {:name "Stanford" :slug "stanford" :type "institution"})
          mit      (crud/create! ds :affiliations {:name "MIT" :slug "mit" :type "institution"})
          nyer     (crud/create! ds :affiliations {:name "The New Yorker" :slug "tny" :type "magazine"})]
      (crud/create! ds :author-affiliations {:author-id      (:authors/id didion)
                                             :affiliation-id (:affiliations/id stanford)
                                             :is-primary     true})
      (crud/create! ds :author-affiliations {:author-id      (:authors/id didion)
                                             :affiliation-id (:affiliations/id mit)
                                             :is-primary     false})
      ;; A publication link must NOT surface as an institutional affiliation —
      ;; "published in" is derived from works, not stored here.
      (crud/create! ds :author-affiliations {:author-id      (:authors/id didion)
                                             :affiliation-id (:affiliations/id nyer)
                                             :is-primary     false})

      (testing "returns only institutions, primary first then by name"
        (let [insts (authors/institutions-of ds (:authors/id didion))]
          (is (= ["Stanford" "MIT"] (map :name insts))
              "the primary institution sorts ahead; the magazine is excluded")
          (is (= {:name "Stanford" :slug "stanford" :primary? true} (first insts)))))

      (testing "an author with no institutional affiliations yields an empty seq"
        (is (= [] (authors/institutions-of ds (:authors/id smith))))))))
