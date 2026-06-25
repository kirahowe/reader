(ns reader.eval.ui-test
  "Rendering tests for the evals dashboard pages: each view surfaces the right
   fields from the inspect maps and links cases to their drill-down."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reader.eval.ui :as ui]))

(deftest pipeline-toggle-is-surface-aware
  (testing "the Overview toggle switches pipeline in place (?p=), not off to Cases"
    (let [out (ui/tagging-overview {})]
      (is (str/includes? out "/overview?p=extraction"))
      (is (not (str/includes? out "href=\"/extraction\"")) "must not jump to the Cases route")))
  (testing "the Workbench toggle stays on the Workbench"
    (let [out (ui/tagging-workbench {:queue {:labeled 0 :total 1 :failed 0 :low-conf 0}
                                     :position 1 :total 1 :readable-type "article"
                                     :readable-id "x" :title "A case" :tags []})]
      (is (str/includes? out "/workbench?p=extraction"))))
  (testing "the Cases toggle uses the distinct Cases routes"
    (is (str/includes? (ui/tagging-index []) "href=\"/extraction\""))))

(deftest runs-list-fragment-test
  (testing "a running run makes the list poll itself"
    (is (str/includes?
         (ui/fragment-html (ui/runs-list-fragment [{:id "r1" :model "stub" :status "running"
                                                    :created-at "2026-06-24T00:00"}]))
         "data-on-interval__duration.2s")))
  (testing "once every run is settled the poll attribute is gone, so polling stops"
    (let [out (ui/fragment-html (ui/runs-list-fragment [{:id "r1" :model "stub" :status "done"
                                                         :n 1 :precision 1.0 :recall 1.0 :f1 1.0
                                                         :created-at "2026-06-24T00:00"}]))]
      (is (not (str/includes? out "data-on-interval")))
      (is (str/includes? out "stub"))))
  (testing "a failed run shows its error instead of a score"
    (let [out (ui/fragment-html (ui/runs-list-fragment [{:id "r1" :model "stub" :status "failed"
                                                         :error "boom" :created-at "2026-06-24T00:00"}]))]
      (is (str/includes? out "failed"))
      (is (str/includes? out "boom")))))

(deftest tagging-index-test
  (let [out (ui/tagging-index [{:readable-type "article" :readable-id "abc"
                                :title "Attention Is All You Need" :outcome "done"
                                :model "gpt-4o-mini" :tag-count 3 :duration-ms 880}])]
    (testing "a row links to its drill-down and shows the attempt's signals"
      ;; & is HTML-escaped to &amp; in the attribute, so assert the parts.
      (is (str/includes? out "/tagging/case?type=article"))
      (is (str/includes? out "id=abc"))
      (is (str/includes? out "Attention Is All You Need"))
      (is (str/includes? out "gpt-4o-mini")))
    (testing "empty state when there are no attempts"
      (is (str/includes? (ui/tagging-index []) "No tagging attempts recorded yet.")))))

(deftest tagging-detail-test
  (let [out (ui/tagging-detail
             {:event {:outcome "done" :model "gpt-4o-mini" :tag-count 3 :duration-ms 880}
              :proposed ["machine learning" "nlp" "transformers"]
              :vocab-size 12
              :assigned [{:label "machine learning" :confidence 0.95}
                         {:label "nlp" :confidence 0.8}]
              :readable {:title "Attention Is All You Need" :abstract "We propose..." :word-count 4200}})]
    (testing "shows what the model proposed vs. what was assigned"
      (is (str/includes? out "Proposed by model"))
      (is (str/includes? out "transformers"))
      (is (str/includes? out "Assigned to baseline"))
      (is (str/includes? out "0.95")))
    (testing "renders a confidence meter for assigned tags"
      (is (str/includes? out "meter")))
    (testing "shows the content the model saw"
      (is (str/includes? out "We propose...")))))

(deftest extraction-index-test
  (let [out (ui/extraction-index [{:url "https://www.example-news.com/scoop"
                                   :domain "example-news.com" :outcome "done"
                                   :body-confidence 0.85 :entity-confidence 0.9
                                   :author-count 2 :author-source "json-ld"}])]
    (testing "a row links to its url-keyed drill-down and shows coverage"
      (is (str/includes? out "/extraction/case?url=https"))
      (is (str/includes? out "example-news.com"))
      (is (str/includes? out "0.85")))))

(deftest extraction-detail-test
  (let [out (ui/extraction-detail
             {:event {:outcome "done" :body-confidence 0.85 :entity-confidence 0.9
                      :author-count 2 :extractor "readability4j"
                      :title-source "json-ld" :author-source "json-ld"
                      :affiliation-source "og" :published-source nil}
              :provenance {:body-signals {:link-density 0.1}}
              :article {:title "The Scoop" :word-count 1200}
              :authors [{:name "Jane Roe"} {:name "John Doe"}]
              :affiliation {:name "Example News" :type "newspaper"}}
             "https://www.example-news.com/scoop")]
    (testing "shows per-field provenance, including a missed field as none"
      (is (str/includes? out "Title"))
      (is (str/includes? out "json-ld"))
      (is (str/includes? out "Published"))
      (is (re-find #"missed[^>]*>none" out)))
    (testing "shows the resolved byline in order and the source"
      (is (str/includes? out "Jane Roe"))
      (is (str/includes? out "John Doe"))
      (is (str/includes? out "Example News")))
    (testing "exposes the raw provenance bag for deep inspection"
      (is (str/includes? out "link-density")))))
