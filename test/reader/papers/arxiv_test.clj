(ns reader.papers.arxiv-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reader.papers.arxiv :as arxiv]))

(deftest sanitize-keeps-mathml-strips-scripts
  (let [html "<h1>Title</h1><p>energy <math><msup><mi>e</mi><mn>2</mn></msup></math> here</p>
              <a href=\"sec1\">jump</a><script>alert(1)</script>"
        out  (arxiv/sanitize html "https://arxiv.org/html/2401.12345")]
    (testing "MathML survives so equations render natively"
      (is (str/includes? out "<math"))
      (is (str/includes? out "<msup"))
      (is (str/includes? out "<mi>e</mi>")))
    (testing "semantic structure survives, scripts are stripped"
      (is (str/includes? out "<h1>Title</h1>"))
      (is (not (str/includes? out "alert")))
      (is (not (str/includes? out "<script"))))
    (testing "relative links resolve against the base URI"
      (is (str/includes? out "https://arxiv.org/html/")))))

(deftest fetch-body-prefers-arxiv-then-ar5iv
  (testing "uses arXiv's native HTML when present"
    (let [out (arxiv/fetch-body (fn [url] (when (str/starts-with? url "https://arxiv.org/html")
                                            "<p>native <math><mi>x</mi></math></p>"))
                                "2401.12345")]
      (is (str/includes? out "native"))
      (is (str/includes? out "<math"))))
  (testing "falls back to ar5iv when arXiv has no HTML"
    (let [out (arxiv/fetch-body (fn [url] (when (str/includes? url "ar5iv") "<p>ar5iv body</p>"))
                                "hep-th/9901001")]
      (is (str/includes? out "ar5iv body"))))
  (testing "nil when neither serves it"
    (is (nil? (arxiv/fetch-body (constantly nil) "2401.12345")))))
