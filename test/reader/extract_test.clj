(ns reader.extract-test
  "Unit tests for the extraction seam. Pure — `extract` takes a
   `reader.domain.readables/find-one` payload ({:item :row}) and returns the uniform
   content map the reader view renders. Bodies are placeholders this phase, so we
   assert the real fields (title/byline/source/links) precisely and just check
   the placeholder body surfaces the right text."
  (:require [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
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

(deftest extract-article-renders-stored-body-test
  (testing "once extracted, the sanitized body_html is rendered raw and wins over the abstract"
    (let [out  (extract/extract
                {:item {:type :article :title "Real" :source nil :authors []}
                 :row  {:articles/body-html     "<p>The actual extracted body of the piece.</p>"
                        :articles/abstract       "an abstract that should be ignored now"
                        :articles/canonical-url  "https://x.test/real"}})
          rendered (str (h/html (:body out)))]
      (is (re-find #"The actual extracted body" rendered))
      (is (re-find #"<p>" rendered) "stored markup is rendered, not escaped")
      (is (not (re-find #"(?i)not available in the reader" rendered)) "no placeholder when a body exists")
      (is (not (re-find #"abstract that should be ignored" rendered))))))

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

(deftest extract-paper-renders-stored-body-test
  (testing "once extracted, the paper's reflowable body is rendered raw (MathML and all) over the abstract"
    (let [out      (extract/extract
                    {:item {:type :paper :title "Attention" :source nil :authors []}
                     :row  {:papers/body-html "<p>The reflowed body <math><mi>x</mi></math> here.</p>"
                            :papers/abstract  "an abstract that should be ignored now"
                            :papers/doi nil :papers/arxiv-id nil}})
          rendered (str (h/html (:body out)))]
      (is (re-find #"The reflowed body" rendered))
      (is (re-find #"<math" rendered) "MathML survives to the reader so equations render")
      (is (not (re-find #"abstract that should be ignored" rendered)))
      (is (not (re-find #"(?i)not available in the reader" rendered))))))

(deftest extract-newsletter-test
  (let [out      (extract/extract
                  {:item {:type :newsletter-issue :title "ACT links for the week"
                          :source {:name "ACT" :slug "act"} :authors []}
                   :row  {:newsletter-issues/sent-at         nil
                          :newsletter-issues/body-html       "<h1>This week's links</h1><p>Body.</p>"
                          :newsletter-issues/unsubscribe-url "https://act.test/unsub"}})
        rendered (str (h/html (:body out)))]
    (testing "a newsletter issue has no external links but carries its unsubscribe url"
      (is (= "ACT links for the week" (:title out)))
      (is (= [] (:links out)))
      (is (= "https://act.test/unsub" (:unsubscribe-url out))))
    (testing "its stored (ingest-sanitized) body is rendered raw, not a placeholder"
      (is (= :newsletter-issue (:kind out)))
      (is (re-find #"This week's links" rendered))
      (is (re-find #"class=\"prose newsletter-body\"" rendered))
      (is (re-find #"data-content-kind=\"newsletter\"" rendered))
      (is (re-find #"<h1>" rendered) "stored markup is rendered, not escaped")
      (is (not (re-find #"(?i)not available in the reader" rendered))))))

(deftest extract-newsletter-without-body-test
  (testing "a newsletter issue with no body falls back to the placeholder"
    (let [out (extract/extract
               {:item {:type :newsletter-issue :title "Empty" :source nil :authors []}
                :row  {:newsletter-issues/sent-at nil :newsletter-issues/body-html ""}})]
      (is (re-find #"(?i)not available in the reader" (body-text (:body out)))))))
