(ns reader.admin-test
  "Aggregation tests for the eval dashboard against a real embedded Postgres:
   record a handful of extraction events and assert the summary the dashboard
   renders from."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [reader.admin :as admin]
            [reader.ingest.entities :as entities]
            [reader.ingest.events :as events]
            [reader.ingest.extract :as extract]
            [reader.test-support.setup :refer [with-system]]))

(defn- ctx [name url]
  (extract/extract (slurp (io/resource (str "reader/ingest/fixtures/" name))) url))

(deftest summary-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          ex  (ctx "jsonld-article.html" "https://www.example-news.com/x")
          ent (entities/from-metadata ex)]
      (events/record! ds {:url "https://www.example-news.com/a" :outcome :done
                          :extract ex :entities ent :durations {:fetch-ms 10 :extract-ms 20}})
      (events/record! ds {:url "https://www.example-news.com/b" :outcome :done
                          :extract ex :entities ent :durations {:fetch-ms 30 :extract-ms 40}})
      (events/record! ds {:url "https://blocked.example/c" :outcome :failed :error-class :blocked-url})

      (let [{:keys [overview coverage by-domain errors recovery]} (admin/summary ds)]
        (testing "overview tallies outcomes"
          (is (= 3 (:total overview)))
          (is (= 2 (:done overview)))
          (is (= 1 (:failed overview))))
        (testing "author coverage is grouped by provenance source"
          (let [by-src (into {} (map (juxt :source :n)) (:author coverage))]
            (is (= 2 (get by-src "json-ld")))
            (is (= 1 (get by-src nil)) "the failure contributes a null author source")))
        (testing "domains group the successes"
          (is (= 2 (:n (first (filter #(= "example-news.com" (:domain %)) by-domain))))))
        (testing "failures are tallied by class"
          (is (= 1 (:n (first (filter #(= "blocked-url" (:error-class %)) errors))))))
        (testing "recovery counts failed urls and how many now have a body"
          (is (= 1 (:failed-urls recovery)))
          (is (= 0 (:recovered recovery))))))))
