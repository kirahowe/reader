(ns reader.dev.seed-test
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [reader.db.crud :as crud]
            [reader.dev.seed :as seed]
            [reader.storage :as storage]
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
    (let [ds    (:reader.db/datasource system)
          store (:reader.storage/store system)]
      (seed/seed! ds store)

      (testing "every table the seeder touches has rows"
        (doseq [table [:authors :affiliations :author-affiliations
                       :newsletter-sources :articles :papers :newsletter-issues
                       :authorships :users :email-inboxes :queue-items :jobs]]
          (is (pos? (count (crud/find-many ds table)))
              (str "expected at least one row in " table))))

      (testing "fixtures land with the expected shape (spot-check)"
        (is (some? (crud/find-1 ds :authors {:slug "joan-didion"})))
        (is (= 6 (count (crud/find-many ds :authorships))))
        (is (= "Attention Is All You Need"
               (-> (crud/find-1 ds :papers {:arxiv-id "1706.03762"})
                   :papers/title))
            "jsonb payloads and uuid FKs round-trip cleanly"))

      (testing "the direct-seeded library carries real body HTML, so the reader
                renders content immediately rather than waiting on a job"
        (doseq [slug ["the-white-album" "slouching-towards-bethlehem"
                      "the-search-for-marvin-gardens"]]
          (is (not-empty (:articles/body-html (crud/find-1 ds :articles {:slug slug})))
              (str "library article has no body_html: " slug)))
        (doseq [arxiv-id ["1706.03762" "1512.03385"]]
          (is (not-empty (:papers/body-html (crud/find-1 ds :papers {:arxiv-id arxiv-id})))
              (str "library paper has no body_html: " arxiv-id)))
        (is (re-find #"<math"
                     (:papers/body-html (crud/find-1 ds :papers {:arxiv-id "1706.03762"})))
            "the Attention paper keeps its MathML so equation reflow is exercised"))

      (testing "the library papers' extract-paper jobs are recorded done with the
                real payload shape, so the dev worker never re-runs them"
        (let [done (filter #(and (= "extract-paper" (:jobs/queue-name %))
                                 (= "done" (:jobs/state %)))
                           (crud/find-many ds :jobs))]
          (is (= 2 (count done)))
          (is (every? #(every? (:jobs/payload %) [:paper-id :kind :id]) done)
              "a done job that lost :kind/:id would crash the handler on re-run")))

      (testing "one real job of each type is enqueued through its production entry
                point — the live pipeline the dev worker exercises. Asserting the
                payload shape here catches a drifted enqueue before the worker does."
        (let [pending  (group-by :jobs/queue-name
                                 (filter (comp #{"pending"} :jobs/state)
                                         (crud/find-many ds :jobs)))
              one      (fn [q] (let [js (pending q)]
                                 (is (= 1 (count js)) (str "one pending " q))
                                 (:jobs/payload (first js))))]
          (testing ":extract-article has the placeholder id and the url to fetch"
            (let [p (one "extract-article")]
              (is (contains? p :article-id))
              (is (= "https://paulgraham.com/greatwork.html" (:url p)))))
          (testing ":extract-paper has the ref kind/id the handler dispatches on"
            (let [p (one "extract-paper")]
              (is (= "arxiv" (:kind p)))
              (is (= "1810.04805" (:id p)))
              (is (contains? p :paper-id))))
          (testing ":ingest-email has the user and the stored object key"
            (let [p (one "ingest-email")]
              (is (contains? p :user-id))
              (is (not-empty (:r2-key p)))
              (is (some? (storage/get-object store (:r2-key p)))
                  "the raw .eml the job will parse was actually stored")))))

      (testing "the seed is multi-user with an overlapping queue"
        (is (<= 2 (count (crud/find-many ds :users))) "at least two users")
        (let [shared (jdbc/execute! ds
                                    ["SELECT readable_type, readable_id
                                      FROM queue_items
                                      GROUP BY readable_type, readable_id
                                      HAVING count(DISTINCT user_id) > 1"])]
          (is (seq shared)
              "at least one readable sits in more than one user's queue")))

      (testing "every newsletter_source points at a 'newsletter'-type affiliation"
        ;; The schema notes this invariant is application-enforced (no DB
        ;; constraint). Guard it here so a seed that drifts gets caught.
        (let [mismatched (jdbc/execute! ds
                                        ["SELECT a.type FROM newsletter_sources ns
                                          JOIN affiliations a ON a.id = ns.affiliation_id
                                          WHERE a.type <> 'newsletter'"])]
          (is (empty? mismatched)))))))
