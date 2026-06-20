(ns reader.domain.readables-test
  "Unit tests for the pure readable-catalog assembly. No database — `assemble`
   takes the already-fetched rows (kebab-qualified, exactly as
   `reader.db.crud` returns them) and produces the normalized catalog the
   per-user queue is drawn from."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.domain.readables :as readables]))

(def ^:private ny   #uuid "00000000-0000-0000-0000-0000000000a1")
(def ^:private act  #uuid "00000000-0000-0000-0000-0000000000a2")

(def ^:private didion #uuid "00000000-0000-0000-0000-0000000000b1")
(def ^:private mcphee #uuid "00000000-0000-0000-0000-0000000000b2")
(def ^:private smith  #uuid "00000000-0000-0000-0000-0000000000b3")

(def ^:private article-id #uuid "00000000-0000-0000-0000-0000000000c1")
(def ^:private paper-id   #uuid "00000000-0000-0000-0000-0000000000c2")
(def ^:private issue-id   #uuid "00000000-0000-0000-0000-0000000000c3")

(def ^:private fixtures
  {:affiliations
   [{:affiliations/id ny  :affiliations/name "The New Yorker" :affiliations/slug "the-new-yorker" :affiliations/type "magazine"}
    {:affiliations/id act :affiliations/name "ACT"            :affiliations/slug "act"            :affiliations/type "newsletter"}]

   :authors
   [{:authors/id didion :authors/name "Joan Didion" :authors/slug "joan-didion"}
    {:authors/id mcphee :authors/name "John McPhee" :authors/slug "john-mcphee"}
    {:authors/id smith  :authors/name "Zadie Smith" :authors/slug "zadie-smith"}]

   :articles
   [{:articles/id article-id :articles/title "The White Album" :articles/affiliation-id ny}]

   ;; A paper with no source and no authorship — exercises both the
   ;; missing-affiliation and missing-author paths.
   :papers
   [{:papers/id paper-id :papers/title "Attention Is All You Need" :papers/affiliation-id nil}]

   :newsletter-issues
   [{:newsletter-issues/id issue-id :newsletter-issues/subject "ACT links for the week" :newsletter-issues/affiliation-id act}]

   ;; Article byline is two authors, deliberately out of ordinal order in the
   ;; input so the test proves they come back sorted by :ordinal.
   :authorships
   [{:authorships/readable-type "article" :authorships/readable-id article-id :authorships/author-id mcphee :authorships/ordinal 1}
    {:authorships/readable-type "article" :authorships/readable-id article-id :authorships/author-id didion :authorships/ordinal 0}
    {:authorships/readable-type "newsletter_issue" :authorships/readable-id issue-id :authorships/author-id smith :authorships/ordinal 0}]})

(defn- by-id [items id] (first (filter #(= id (:id %)) items)))

(deftest assemble-test
  (let [items (readables/assemble fixtures)
        article (by-id items article-id)
        paper   (by-id items paper-id)
        issue   (by-id items issue-id)]

    (testing "every readable across the three tables appears once"
      (is (= 3 (count items)))
      (is (= #{:article :paper :newsletter-issue} (set (map :type items)))))

    (testing "each item carries the table that backs it"
      (is (= :articles (:table article)))
      (is (= :papers (:table paper)))
      (is (= :newsletter-issues (:table issue))))

    (testing "items are ordered case-insensitively by title"
      (is (= ["ACT links for the week" "Attention Is All You Need" "The White Album"]
             (map :title items))))

    (testing "a newsletter issue uses its subject as the title"
      (is (= "ACT links for the week" (:title issue)))
      (is (= :newsletter-issue (:type issue))))

    (testing "source is the readable's own affiliation (name + slug), or nil"
      (is (= {:name "The New Yorker" :slug "the-new-yorker"} (:source article)))
      (is (= {:name "ACT" :slug "act"} (:source issue)))
      (is (nil? (:source paper)) "a paper with no affiliation has no source"))

    (testing "authors are joined via authorships and ordered by ordinal"
      (is (= [{:name "Joan Didion" :slug "joan-didion"}
              {:name "John McPhee" :slug "john-mcphee"}]
             (:authors article)))
      (is (= [{:name "Zadie Smith" :slug "zadie-smith"}] (:authors issue))))

    (testing "a readable with no authorship has an empty byline"
      (is (= [] (:authors paper))))))
