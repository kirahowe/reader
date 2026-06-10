(ns reader.ingest-test
  "Integration tests for the ingest write path against a real embedded Postgres:
   find-or-create idempotency and the transactional persist! that finalizes a
   placeholder article into a fully extracted, authored row."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [reader.affiliations :as affiliations]
            [reader.authors :as authors]
            [reader.db.crud :as crud]
            [reader.ingest :as ingest]
            [reader.ingest.entities :as entities]
            [reader.ingest.events :as events]
            [reader.ingest.extract :as extract]
            [reader.test-support.setup :refer [with-system]]))

(defn- ctx [name url]
  (extract/extract (slurp (io/resource (str "reader/ingest/fixtures/" name))) url))

(deftest find-or-create!-idempotent-test
  (with-system [system]
    (let [ds (:reader.db/datasource system)]
      (testing "authors: same name returns the same row and derives a sort-name"
        (let [a1 (authors/find-or-create! ds "Joan Didion")
              a2 (authors/find-or-create! ds "Joan Didion")]
          (is (= (:authors/id a1) (:authors/id a2)))
          (is (= "Didion, Joan" (:authors/sort-name a1)))))
      (testing "affiliations: idempotent by slug, with a conservative default type"
        (let [f1 (affiliations/find-or-create! ds "Example News")
              f2 (affiliations/find-or-create! ds "Example News")]
          (is (= (:affiliations/id f1) (:affiliations/id f2)))
          (is (= "other" (:affiliations/type f1)))))
      (testing "authors: a homepage url is stored when provided"
        (let [a (authors/find-or-create! ds "Ada Lovelace" "https://ada.example")]
          (is (= "https://ada.example" (:authors/url a))))))))

(deftest persist!-test
  (with-system [system]
    (let [ds          (:reader.db/datasource system)
          url         "https://www.example-news.com/2026/05/quiet-revolution-type-design"
          placeholder (crud/create! ds :articles {:title url :slug "placeholder" :canonical-url url})
          aid         (:articles/id placeholder)
          ex          (ctx "jsonld-article.html" url)
          ent         (entities/from-metadata ex)]

      (testing "persist! finalizes the placeholder with the extracted data + source"
        (ingest/persist! ds aid ex ent)
        (let [row (crud/by-id ds :articles aid)
              aff (crud/by-id ds :affiliations (:articles/affiliation-id row))]
          (is (= "The Quiet Revolution in Type Design" (:articles/title row)))
          (is (some? (:articles/body-html row)) "body was extracted and stored")
          (is (< 60 (:articles/word-count row)))
          (is (= url (:articles/canonical-url row)) "canonical url is preserved")
          (is (= "Example News" (:affiliations/name aff)))))

      (testing "authorships are attached in byline order"
        (let [names (->> (crud/find-many ds :authorships {:readable-id aid})
                         (sort-by :authorships/ordinal)
                         (map #(:authors/name (crud/by-id ds :authors (:authorships/author-id %)))))]
          (is (= ["Mara Whitfield" "Devon Park"] names))))

      (testing "re-running persist! is idempotent — authorships are replaced, not duplicated"
        (ingest/persist! ds aid ex ent)
        (is (= 2 (count (crud/find-many ds :authorships {:readable-id aid}))))))))

(deftest record!-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          ex  (ctx "jsonld-article.html" "https://www.example-news.com/x")
          ent (entities/from-metadata ex)]
      (testing "a success event persists with first-class columns + jsonb provenance"
        (let [row  (events/record! ds {:url "https://www.example-news.com/x" :outcome :done
                                       :extract ex :entities ent :durations {:fetch-ms 5 :extract-ms 9}})
              back (crud/by-id ds :extraction-events (:extraction-events/id row))]
          (is (= "done" (:extraction-events/outcome back)))
          (is (= "example-news.com" (:extraction-events/domain back)))
          (is (= "json-ld" (:extraction-events/author-source back)))
          (is (= 2 (:extraction-events/author-count back)))
          (is (< 60 (:extraction-events/word-count back)))
          (is (= 5 (:extraction-events/fetch-ms back)))
          (is (= "json-ld" (get-in (:extraction-events/provenance back) [:coverage :published-at])))))
      (testing "a failure event persists its error class and leaves metrics null"
        (let [row  (events/record! ds {:url "https://bad.example/x" :outcome :failed :error-class :blocked-url})
              back (crud/by-id ds :extraction-events (:extraction-events/id row))]
          (is (= "failed" (:extraction-events/outcome back)))
          (is (= "blocked-url" (:extraction-events/error-class back)))
          (is (nil? (:extraction-events/word-count back))))))))

(deftest extract-article!-handler-test
  (with-system [system]
    (let [ds          (:reader.db/datasource system)
          url         "https://www.example-news.com/quiet"
          placeholder (crud/create! ds :articles {:title url :slug "placeholder-2" :canonical-url url})
          aid         (:articles/id placeholder)
          html        (slurp (io/resource "reader/ingest/fixtures/jsonld-article.html"))
          stub-fetch  (fn [_] {:html html :final-url url})]
      (ingest/extract-article! ds {:article-id (str aid) :url url}
                               {:fetch-fn stub-fetch :extract-entities entities/from-metadata})
      (testing "the placeholder is finalized end-to-end and a success event recorded"
        (is (= "The Quiet Revolution in Type Design" (:articles/title (crud/by-id ds :articles aid))))
        (is (some? (:articles/body-html (crud/by-id ds :articles aid))))
        (is (= 2 (count (crud/find-many ds :authorships {:readable-id aid}))))
        (is (= "done" (:extraction-events/outcome (crud/find-1 ds :extraction-events {:url url}))))))))

(deftest extract-article!-records-failure-test
  (with-system [system]
    (let [ds   (:reader.db/datasource system)
          url  "https://blocked.example/x"
          boom (fn [_] (throw (ex-info "blocked" {:error-class :blocked-url})))]
      (is (thrown? Exception
                   (ingest/extract-article! ds {:article-id (str (random-uuid)) :url url}
                                            {:fetch-fn boom :extract-entities entities/from-metadata})))
      (testing "the failure event captures the error class"
        (is (= "blocked-url" (:extraction-events/error-class (crud/find-1 ds :extraction-events {:url url}))))))))

(deftest start!-and-status-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          uid (:users/id (crud/create! ds :users {:email "ingest@x.test"}))
          url "https://example.com/some-article"]
      (testing "start! creates a placeholder article, queues it, and enqueues one job"
        (let [{:keys [article queue-item]} (ingest/start! ds uid url)]
          (is (= url (:articles/canonical-url article)))
          (is (= url (:articles/title article)) "placeholder title is the url")
          (is (nil? (:articles/body-html article)))
          (is (= uid (:queue-items/user-id queue-item)))
          (is (= 1 (count (crud/find-many ds :jobs {:queue-name "extract-article"}))))
          (is (= :importing (ingest/status ds (:articles/id article))))))
      (testing "re-pasting the same url reuses the article and enqueues no second job"
        (let [{:keys [article]} (ingest/start! ds uid url)]
          (is (= 1 (count (crud/find-many ds :jobs {:queue-name "extract-article"}))))
          (testing "status flips to done once a body is present"
            (crud/update! ds :articles (:articles/id article) {:body-html "<p>hi</p>"})
            (is (= :done (ingest/status ds (:articles/id article))))))))))

(deftest start!-dated-version-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          uid (:users/id (crud/create! ds :users {:email "dated@x.test"}))
          url "https://example.com/changes-daily"
          {a1 :article} (ingest/start! ds uid url)]
      (testing "a same-day re-paste reuses the version: one article, one job"
        (ingest/start! ds uid url)
        (is (= 1 (count (crud/find-many ds :articles {:canonical-url url}))))
        (is (= 1 (count (crud/find-many ds :jobs {:queue-name "extract-article"})))))
      (testing "a later-day paste of the same url is a new version with its own job"
        ;; backdate the first version so today's (url, current_date) key is fresh
        (crud/update! ds :articles (:articles/id a1) {:fetched-on (java.time.LocalDate/of 2020 1 1)})
        (let [{a2 :article} (ingest/start! ds uid url)]
          (is (not= (:articles/id a1) (:articles/id a2)) "a distinct article version")
          (is (= 2 (count (crud/find-many ds :articles {:canonical-url url}))))
          (is (= 2 (count (crud/find-many ds :jobs {:queue-name "extract-article"})))))))))

(deftest status-failed-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          uid (:users/id (crud/create! ds :users {:email "fail@x.test"}))
          {:keys [article]} (ingest/start! ds uid "https://example.com/bad")
          job (crud/find-1 ds :jobs {:queue-name "extract-article"})]
      (crud/update! ds :jobs (:jobs/id job) {:state "failed" :last-error "blocked"})
      (testing "a terminally failed extract job reports :failed even with no body"
        (is (= :failed (ingest/status ds (:articles/id article))))))))
