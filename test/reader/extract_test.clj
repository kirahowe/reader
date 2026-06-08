(ns reader.extract-test
  "Unit tests for the extraction seam. Pure — `extract` takes a
   `reader.readables/find-one` payload ({:item :row}) and returns the uniform
   content map the reader view renders. Bodies are placeholders this phase, so we
   assert the real fields (title/byline/source/links) precisely and just check
   the placeholder body surfaces the right text."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.extract :as extract]))

(defn- body-text
  "The text content of a hiccup body, flattened to a string for searching."
  [body]
  (pr-str body))

(deftest extract-article-test
  (let [out (extract/extract
             {:item {:type    :article
                     :title   "On Engines"
                     :source  {:name "The Source" :slug "the-source"}
                     :authors [{:name "Ada Lovelace" :slug "ada-lovelace"}]}
              :row  {:articles/abstract      "A note."
                     :articles/canonical-url "https://x.test/engines"
                     :articles/published-at  nil}})]
    (testing "common fields come straight from the normalized item"
      (is (= "On Engines" (:title out)))
      (is (= {:name "The Source" :slug "the-source"} (:source out)))
      (is (= [{:name "Ada Lovelace" :slug "ada-lovelace"}] (:authors out))))
    (testing "an article links to its canonical URL"
      (is (= [{:label "View original" :href "https://x.test/engines"}] (:links out))))
    (testing "the placeholder body previews the abstract and marks itself a stub"
      (is (re-find #"A note\." (body-text (:body out))))
      (is (re-find #"(?i)not available in the reader" (body-text (:body out)))))))

(deftest extract-paper-test
  (let [out (extract/extract
             {:item {:type :paper :title "Attention Is All You Need" :source nil :authors []}
              :row  {:papers/abstract     "The Transformer."
                     :papers/doi          "10.48550/arXiv.1706.03762"
                     :papers/arxiv-id     "1706.03762"
                     :papers/published-at nil}})]
    (testing "a paper links out to DOI and arXiv when present"
      (is (= [{:label "DOI"   :href "https://doi.org/10.48550/arXiv.1706.03762"}
              {:label "arXiv" :href "https://arxiv.org/abs/1706.03762"}]
             (:links out))))
    (testing "the abstract is previewed in the placeholder body"
      (is (re-find #"The Transformer\." (body-text (:body out)))))))

(deftest extract-paper-without-ids-test
  (testing "a paper with no DOI or arXiv id surfaces no external links"
    (is (= [] (:links (extract/extract
                       {:item {:type :paper :title "Bare" :source nil :authors []}
                        :row  {:papers/abstract nil :papers/doi nil :papers/arxiv-id nil}}))))))

(deftest extract-newsletter-test
  (let [out (extract/extract
             {:item {:type :newsletter-issue :title "ACT links for the week"
                     :source {:name "ACT" :slug "act"} :authors []}
              :row  {:newsletter-issues/sent-at   nil
                     :newsletter-issues/body-html "<h1>hi</h1>"}})]
    (testing "a newsletter issue has no external links and a placeholder body"
      (is (= "ACT links for the week" (:title out)))
      (is (= [] (:links out)))
      (is (re-find #"(?i)not available in the reader" (body-text (:body out)))))))
