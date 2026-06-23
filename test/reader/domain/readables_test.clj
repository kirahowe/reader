(ns reader.domain.readables-test
  "Tests for the readable catalog. The pure `assemble` core takes already-fetched
   rows (kebab-qualified, exactly as `reader.db.crud` returns them) and produces
   the normalized catalog the per-user queue is drawn from. The DB-backed browse
   views (`by-author` / `by-source`) and the pure section-derivers built on them
   (`sources-of` / `contributors-of`) are covered against a real datasource."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.db.crud :as crud]
            [reader.domain.authors :as authors]
            [reader.domain.readables :as readables]
            [reader.test-support.setup :refer [with-system]]))

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
   [{:articles/id article-id :articles/title "The White Album" :articles/affiliation-id ny
     :articles/canonical-url "https://x.test/white-album"}]

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

    (testing "source is the readable's own affiliation (name + slug + type), or nil"
      (is (= {:name "The New Yorker" :slug "the-new-yorker" :type "magazine"} (:source article)))
      (is (= {:name "ACT" :slug "act" :type "newsletter"} (:source issue)))
      (is (nil? (:source paper)) "a paper with no affiliation has no source"))

    (testing "each item carries an external url — nil when there is no public original"
      (is (= "https://x.test/white-album" (:url article)) "the article's canonical url")
      (is (nil? (:url paper)) "a paper with no doi/arxiv id has no external url")
      (is (nil? (:url issue)) "a newsletter issue is private — no external original"))

    (testing "authors are joined via authorships and ordered by ordinal"
      (is (= [{:name "Joan Didion" :slug "joan-didion"}
              {:name "John McPhee" :slug "john-mcphee"}]
             (:authors article)))
      (is (= [{:name "Zadie Smith" :slug "zadie-smith"}] (:authors issue))))

    (testing "a readable with no authorship has an empty byline"
      (is (= [] (:authors paper))))))

(deftest by-author-test
  (with-system [system]
    (let [ds     (:reader.db/datasource system)
          nyer   (crud/create! ds :affiliations {:name "The New Yorker" :slug "tny" :type "magazine"})
          nature (crud/create! ds :affiliations {:name "Nature" :slug "nature" :type "journal"})
          act    (crud/create! ds :affiliations {:name "ACT" :slug "act" :type "newsletter"})
          didion (authors/create! ds {:name "Joan Didion" :slug "jd"})
          art    (crud/create! ds :articles {:affiliation-id (:affiliations/id nyer)
                                             :title "The White Album" :slug "wa"
                                             :canonical-url "https://x.test/wa"})
          paper  (crud/create! ds :papers {:affiliation-id (:affiliations/id nature)
                                           :title "A Paper" :doi "10.1/x" :pdf-object-key "p.pdf"})
          issue  (crud/create! ds :newsletter-issues {:affiliation-id (:affiliations/id act)
                                                      :subject "Private issue" :body-html ""
                                                      :raw-email-object-key "k"})
          link   (fn [type id] (crud/create! ds :authorships {:author-id (:authors/id didion)
                                                              :readable-type type :readable-id id :ordinal 0}))]
      (link "article" (:articles/id art))
      (link "paper" (:papers/id paper))
      (link "newsletter_issue" (:newsletter-issues/id issue))

      (testing "returns the author's articles and papers, joined to source + external url"
        (let [works (readables/by-author ds (:authors/id didion))]
          (is (= #{"The White Album" "A Paper"} (set (map :title works)))
              "the private newsletter issue is excluded")
          (is (= "https://x.test/wa"
                 (:url (first (filter #(= "The White Album" (:title %)) works)))))
          (is (= "https://doi.org/10.1/x"
                 (:url (first (filter #(= "A Paper" (:title %)) works))))
              "a paper links out to its DOI")))

      (testing "sources-of derives the distinct 'published in' set, by name"
        (let [works (readables/by-author ds (:authors/id didion))]
          (is (= ["Nature" "The New Yorker"] (map :name (readables/sources-of works))))))

      (testing "an author credited on nothing yields an empty catalog"
        (let [smith (authors/create! ds {:name "Zadie Smith" :slug "zs"})]
          (is (= [] (readables/by-author ds (:authors/id smith)))))))))

(deftest by-source-test
  (with-system [system]
    (let [ds     (:reader.db/datasource system)
          nyer   (crud/create! ds :affiliations {:name "The New Yorker" :slug "tny" :type "magazine"})
          other  (crud/create! ds :affiliations {:name "Harper's" :slug "harpers" :type "magazine"})
          didion (authors/create! ds {:name "Joan Didion" :slug "jd"})
          mcphee (authors/create! ds {:name "John McPhee" :slug "jm"})
          mk     (fn [aff title slug] (crud/create! ds :articles {:affiliation-id (:affiliations/id aff)
                                                                  :title title :slug slug
                                                                  :canonical-url (str "https://x.test/" slug)}))
          wa     (mk nyer "The White Album" "wa")
          mg     (mk nyer "Marvin Gardens" "mg")
          ob     (mk other "On Beauty" "ob")
          link   (fn [author id] (crud/create! ds :authorships {:author-id (:authors/id author)
                                                                :readable-type "article" :readable-id id :ordinal 0}))]
      (link didion (:articles/id wa))
      (link mcphee (:articles/id mg))
      (link didion (:articles/id ob))

      (testing "returns only this source's works"
        (let [works (readables/by-source ds (:affiliations/id nyer))]
          (is (= #{"The White Album" "Marvin Gardens"} (set (map :title works)))
              "On Beauty (a different source) is absent")))

      (testing "contributors-of derives the distinct authors, by name"
        (let [works (readables/by-source ds (:affiliations/id nyer))]
          (is (= ["Joan Didion" "John McPhee"] (map :name (readables/contributors-of works))))))

      (testing "a source with no works yields an empty catalog"
        (is (= [] (readables/by-source ds (random-uuid))))))))
