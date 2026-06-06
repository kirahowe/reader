(ns reader.authors-test
  "Tests for `reader.authors`: the pure sort-name heuristic plus the
   create!/list-sorted integration behaviour it feeds."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.authors :as authors]
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

(deftest affiliations-of-test
  (with-system [system]
    (let [ds      (:reader.db/datasource system)
          didion  (authors/create! ds {:name "Joan Didion" :slug "jd"})
          smith   (authors/create! ds {:name "Zadie Smith" :slug "zs"})
          ny      (crud/create! ds :affiliations {:name "The New Yorker" :slug "tny" :type "magazine"})
          harpers (crud/create! ds :affiliations {:name "Harper's" :slug "harpers" :type "magazine"})]
      (crud/create! ds :author-affiliations {:author-id      (:authors/id didion)
                                             :affiliation-id (:affiliations/id ny)
                                             :role           "staff writer"
                                             :is-primary     true})
      (crud/create! ds :author-affiliations {:author-id      (:authors/id didion)
                                             :affiliation-id (:affiliations/id harpers)
                                             :role           "contributor"
                                             :is-primary     false})

      (testing "returns the author's affiliations, primary first"
        (let [affs (authors/affiliations-of ds (:authors/id didion))]
          (is (= ["The New Yorker" "Harper's"] (map :name affs))
              "the primary affiliation sorts ahead of the rest")
          (is (= {:name "The New Yorker" :slug "tny" :type "magazine"
                  :role "staff writer" :primary? true}
                 (first affs)))))

      (testing "an author with no affiliations yields an empty seq"
        (is (= [] (authors/affiliations-of ds (:authors/id smith))))))))
