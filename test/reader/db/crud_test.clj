(ns reader.db.crud-test
  "Integration tests for the generic `reader.db.crud` helpers."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [reader.db.crud :as crud]
            [reader.test-support.setup :refer [with-system]]))

(deftest by-id-test
  (with-system [system]
    (let [ds (:reader.db/datasource system)
          {:authors/keys [id]}
          (jdbc/execute-one! ds
                             ["INSERT INTO authors (name, sort_name, slug)
                               VALUES ('Joan Didion', 'Didion, Joan', 'joan-didion-1')
                               RETURNING id"]
                             {:builder-fn rs/as-kebab-maps})]

      (testing "returns the row whose id matches"
        (let [row (crud/by-id ds :authors id)]
          (is (= "Joan Didion" (:authors/name row)))
          (is (= id (:authors/id row)))))

      (testing "returns nil when no row matches"
        (is (nil? (crud/by-id ds :authors #uuid "00000000-0000-0000-0000-000000000000")))))))

(deftest create!-test
  (with-system [system]
    (let [ds (:reader.db/datasource system)]

      (testing "inserts a row and returns it with db-generated columns filled in"
        (let [row (crud/create! ds :authors {:name      "Zadie Smith"
                                             :sort-name "Smith, Zadie"
                                             :slug      "zadie-smith"})]
          (is (uuid? (:authors/id row)))
          (is (= "Zadie Smith" (:authors/name row)))
          (is (inst? (:authors/created-at row)))
          (testing "the row is actually persisted"
            (is (= row (crud/by-id ds :authors (:authors/id row))))))))))

(deftest update!-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          row (crud/create! ds :authors {:name      "Hilary Mantel"
                                         :sort-name "Mantel, Hilary"
                                         :slug      "hilary-mantel"})]

      (testing "updates the matching row and returns it"
        (let [updated (crud/update! ds :authors (:authors/id row) {:bio "Booker × 2"})]
          (is (= "Booker × 2" (:authors/bio updated)))
          (is (= "Hilary Mantel" (:authors/name updated))
              "untouched columns are preserved")
          (testing "change is persisted"
            (is (= "Booker × 2"
                   (:authors/bio (crud/by-id ds :authors (:authors/id row))))))))

      (testing "returns nil when no row matches"
        (is (nil? (crud/update! ds :authors
                                #uuid "00000000-0000-0000-0000-000000000000"
                                {:bio "ghost"})))))))

(deftest delete!-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          row (crud/create! ds :authors {:name      "Anne Carson"
                                         :sort-name "Carson, Anne"
                                         :slug      "anne-carson"})
          id  (:authors/id row)]

      (testing "removes the row and returns it"
        (let [deleted (crud/delete! ds :authors id)]
          (is (= id (:authors/id deleted)))
          (is (nil? (crud/by-id ds :authors id)))))

      (testing "returns nil when no row matches"
        (is (nil? (crud/delete! ds :authors
                                #uuid "00000000-0000-0000-0000-000000000000")))))))

(deftest find-many-test
  (with-system [system]
    (let [ds (:reader.db/datasource system)]
      (crud/create! ds :affiliations
                    {:name "The New Yorker" :slug "the-new-yorker" :type "magazine"})
      (crud/create! ds :affiliations
                    {:name "Harper's" :slug "harpers" :type "magazine"})
      (crud/create! ds :affiliations
                    {:name "Astral Codex Ten" :slug "act" :type "blog"})

      (testing "returns every matching row"
        (let [rows (crud/find-many ds :affiliations {:type "magazine"})]
          (is (= 2 (count rows)))
          (is (= #{"The New Yorker" "Harper's"}
                 (set (map :affiliations/name rows))))))

      (testing "returns an empty seq when nothing matches"
        (is (empty? (crud/find-many ds :affiliations {:type "newspaper"})))))))

(deftest find-1-test
  (with-system [system]
    (let [ds (:reader.db/datasource system)]
      (crud/create! ds :authors {:name "Joan" :sort-name "Joan" :slug "joan-1"})
      (crud/create! ds :authors {:name "Joan" :sort-name "Joan" :slug "joan-2"})

      (testing "returns the row when exactly one matches"
        (let [row (crud/find-1 ds :authors {:slug "joan-1"})]
          (is (= "joan-1" (:authors/slug row)))))

      (testing "returns nil when nothing matches"
        (is (nil? (crud/find-1 ds :authors {:slug "nobody"}))))

      (testing "throws when more than one row matches"
        (is (thrown? clojure.lang.ExceptionInfo
                     (crud/find-1 ds :authors {:name "Joan"})))))))

(deftest every-crud-only-table-round-trips
  (with-system [system]
    (let [ds      (:reader.db/datasource system)
          author  (crud/create! ds :authors      {:name "A" :sort-name "A" :slug "round-a"})
          aff     (crud/create! ds :affiliations {:name "Aff" :slug "round-aff" :type "blog"})
          user    (crud/create! ds :users        {:email "Round@Reader.Test"})
          article (crud/create! ds :articles
                                {:affiliation-id (:affiliations/id aff)
                                 :title          "Hello"
                                 :slug           "hello"
                                 :canonical-url  "https://example.com/hello"})
          paper   (crud/create! ds :papers
                                {:affiliation-id (:affiliations/id aff)
                                 :title          "On Foo"
                                 :doi            "10.1000/round-trip"
                                 :pdf-object-key "papers/round.pdf"})
          issue   (crud/create! ds :newsletter-issues
                                {:affiliation-id       (:affiliations/id aff)
                                 :subject              "Welcome"
                                 :body-html            "<p>Hi</p>"
                                 :raw-email-object-key "issues/round.eml"})
          aa      (crud/create! ds :author-affiliations
                                {:author-id      (:authors/id author)
                                 :affiliation-id (:affiliations/id aff)
                                 :role           "writer"})
          inbox   (crud/create! ds :email-inboxes
                                {:user-id (:users/id user)
                                 :alias   "round+abc@reader.test"})]

      (doseq [[table row] [[:authors              author]
                           [:affiliations         aff]
                           [:users                user]
                           [:articles             article]
                           [:papers               paper]
                           [:newsletter-issues    issue]
                           [:author-affiliations  aa]
                           [:email-inboxes        inbox]]]
        (testing (str table " round-trips via by-id")
          (let [id-key  (keyword (name table) "id")
                fetched (crud/by-id ds table (get row id-key))]
            (is (= row fetched)))))

      (testing "newsletter-sources round-trips on its non-id PK"
        (let [ns-row (crud/create! ds :newsletter-sources
                                   {:affiliation-id      (:affiliations/id aff)
                                    :inbound-email-alias "letters@reader.test"})]
          (is (= ns-row
                 (crud/find-1 ds :newsletter-sources
                              {:affiliation-id (:affiliations/id aff)}))))))))

(deftest jsonb-columns-round-trip-clojure-maps
  (with-system [system]
    (let [ds   (:reader.db/datasource system)
          user (crud/create! ds :users        {:email "j@reader.test"})
          aff  (crud/create! ds :affiliations {:name "J" :slug "j-aff" :type "blog"})
          art  (crud/create! ds :articles     {:affiliation-id (:affiliations/id aff)
                                               :title          "T" :slug "t"
                                               :canonical-url  "https://j.test/t"})
          via  {:source "manual" :note "hi"}
          row  (crud/create! ds :queue-items
                             {:user-id       (:users/id user)
                              :readable-type "article"
                              :readable-id   (:articles/id art)
                              :via           via})]
      (testing "a map value round-trips through a jsonb column"
        (is (= via (:queue-items/via row))))

      (testing "find-* can filter on a jsonb map value (where values get lifted too)"
        (is (= (:queue-items/id row)
               (:queue-items/id (crud/find-1 ds :queue-items {:via via})))
            "a map in the where clause must bind as jsonb, not parse as a HoneySQL fragment")))))

(deftest create-ignore!-test
  (with-system [system]
    (let [ds (:reader.db/datasource system)]
      (testing "inserts and returns the row when there's no conflict"
        (let [row (crud/create-ignore! ds :authors {:name "Grace Hopper" :slug "ci-grace"})]
          (is (some? row))
          (is (= "Grace Hopper" (:authors/name row)))))

      (testing "returns nil (no throw) when it collides on a unique constraint"
        (crud/create! ds :authors {:name "First" :slug "ci-dup"})
        ;; A plain create! here would raise a unique violation and abort the tx;
        ;; create-ignore! lets resolve-entity! treat a lost race as a re-resolve.
        (is (nil? (crud/create-ignore! ds :authors {:name "Second" :slug "ci-dup"})))
        (is (= 1 (count (crud/find-many ds :authors {:slug "ci-dup"}))) "no second row written")))))
