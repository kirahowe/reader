(ns reader.dev.seed-test
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [reader.db.crud :as crud]
            [reader.dev.seed :as seed]
            [reader.test-support.setup :refer [with-system]]))

(deftest assert-local-url!-refuses-non-local-hosts
  (let [assert-local-url! #'seed/assert-local-url!]
    (testing "local hosts are accepted (no throw)"
      (is (nil? (assert-local-url! "jdbc:postgresql://localhost:5432/postgres")))
      (is (nil? (assert-local-url! "jdbc:postgresql://127.0.0.1:5432/postgres"))))

    (testing "a hosted (prod) URL is refused before any truncate can run"
      (is (thrown? clojure.lang.ExceptionInfo
                   (assert-local-url!
                    "jdbc:postgresql://ep-x.neon.tech:5432/reader?sslmode=require"))))

    (testing "an unparseable URL is refused rather than assumed local"
      (is (thrown? clojure.lang.ExceptionInfo
                   (assert-local-url! "not-a-jdbc-url"))))))

(deftest seed!-populates-every-table
  (with-system [system]
    (let [ds (:reader.db/datasource system)]
      (seed/seed! ds)

      (testing "every table the seeder touches has rows"
        (doseq [table [:authors :affiliations :author-affiliations
                       :newsletter-sources :articles :papers :newsletter-issues
                       :authorships :users :email-inboxes :queue-items :jobs]]
          (is (pos? (count (crud/find-many ds table)))
              (str "expected at least one row in " table))))

      (testing "fixtures land with the expected shape (spot-check)"
        (is (some? (crud/find-1 ds :authors {:slug "joan-didion"})))
        (is (= 2 (count (crud/find-many ds :authorships))))
        (is (= "Attention Is All You Need"
               (-> (crud/find-1 ds :papers {:arxiv-id "1706.03762"})
                   :papers/title))
            "jsonb payloads and uuid FKs round-trip cleanly"))

      (testing "every newsletter_source points at a 'newsletter'-type affiliation"
        ;; The schema notes this invariant is application-enforced (no DB
        ;; constraint). Guard it here so a seed that drifts gets caught.
        (let [mismatched (jdbc/execute! ds
                                        ["SELECT a.type FROM newsletter_sources ns
                                          JOIN affiliations a ON a.id = ns.affiliation_id
                                          WHERE a.type <> 'newsletter'"])]
          (is (empty? mismatched)))))))
