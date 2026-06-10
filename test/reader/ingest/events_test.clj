(ns reader.ingest.events-test
  "Tests for the pure eval-row derivation. Success rows are built from the real
   extractor + entity seam over a fixture, so the provenance the dashboard
   aggregates is the provenance extraction actually produces."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [reader.ingest.entities :as entities]
            [reader.ingest.events :as events]
            [reader.ingest.extract :as extract]))

(defn- ctx [name url]
  (extract/extract (slurp (io/resource (str "reader/ingest/fixtures/" name))) url))

(deftest event-success-test
  (let [extract  (ctx "jsonld-article.html" "https://www.example-news.com/x")
        ent      (entities/from-metadata extract)
        ev       (events/event {:url       "https://www.example-news.com/x"
                                :outcome   :done
                                :extract   extract
                                :entities  ent
                                :durations {:fetch-ms 12 :extract-ms 34}})]
    (testing "outcome, domain (www stripped), and extractor"
      (is (= "done" (:outcome ev)))
      (is (= "example-news.com" (:domain ev)))
      (is (= "readability4j" (:extractor ev)))
      (is (nil? (:error-class ev))))
    (testing "per-field provenance sources are recorded as first-class columns"
      (is (= "json-ld" (:title-source ev)))
      (is (= "json-ld" (:author-source ev)))
      (is (= "og" (:affiliation-source ev)))
      (is (= "json-ld" (:published-source ev))))
    (testing "counts, confidences, and timings"
      (is (= 2 (:author-count ev)))
      (is (< 60 (:word-count ev)))
      (is (> (:body-confidence ev) 0.5))
      (is (> (:entity-confidence ev) 0.9))
      (is (= 12 (:fetch-ms ev)))
      (is (= 34 (:extract-ms ev))))
    (testing "the jsonb provenance bag carries full coverage + body signals"
      (is (= "json-ld" (get-in ev [:provenance :coverage :published-at])))
      (is (contains? (get-in ev [:provenance :body-signals]) :link-density)))))

(deftest event-failure-test
  (let [ev (events/event {:url "https://bad.example/x" :outcome :failed :error-class :blocked-url})]
    (testing "a failure records its class and leaves extraction fields empty"
      (is (= "failed" (:outcome ev)))
      (is (= "blocked-url" (:error-class ev)))
      (is (= "bad.example" (:domain ev)))
      (is (nil? (:extractor ev)))
      (is (nil? (:word-count ev)))
      (is (nil? (:author-count ev)))
      (is (nil? (:author-source ev))))))
