(ns reader.papers-test
  (:require [clojure.test :refer [deftest is testing]]
            [reader.papers :as papers]))

(deftest detect-test
  (testing "arXiv URLs (abs/pdf/html), version suffixes, ar5iv — version stripped"
    (is (= {:kind :arxiv :id "2401.12345"} (papers/detect "https://arxiv.org/abs/2401.12345")))
    (is (= {:kind :arxiv :id "2401.12345"} (papers/detect "arxiv.org/pdf/2401.12345v2")))
    (is (= {:kind :arxiv :id "2401.12345"} (papers/detect "https://arxiv.org/html/2401.12345")))
    (is (= {:kind :arxiv :id "2401.12345"} (papers/detect "https://ar5iv.labs.arxiv.org/html/2401.12345"))))

  (testing "bare arXiv ids, arXiv: prefix, and old-style category ids"
    (is (= {:kind :arxiv :id "2401.12345"}      (papers/detect "2401.12345")))
    (is (= {:kind :arxiv :id "2401.12345"}      (papers/detect "arXiv:2401.12345v3")))
    (is (= {:kind :arxiv :id "hep-th/9901001"}  (papers/detect "https://arxiv.org/abs/hep-th/9901001")))
    (is (= {:kind :arxiv :id "math.GT/0309136"} (papers/detect "math.GT/0309136"))))

  (testing "DOIs — doi.org url and bare, including arXiv's own DOI"
    (is (= {:kind :doi :id "10.1145/3292500.3330701"} (papers/detect "https://doi.org/10.1145/3292500.3330701")))
    (is (= {:kind :doi :id "10.1038/nature12373"}     (papers/detect "10.1038/nature12373")))
    (is (= {:kind :doi :id "10.48550/arxiv.2401.12345"} (papers/detect "https://doi.org/10.48550/arXiv.2401.12345"))))

  (testing "non-paper links and junk are nil"
    (is (nil? (papers/detect "https://example.com/some-article")))
    (is (nil? (papers/detect "just some text")))
    (is (nil? (papers/detect "")))
    (is (nil? (papers/detect nil)))))
