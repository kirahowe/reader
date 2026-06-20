(ns reader.util.slug-test
  (:require [clojure.test :refer [deftest is testing]]
            [reader.util.slug :as slug]))

(deftest slugify-test
  (testing "lowercases and hyphenates word boundaries"
    (is (= "the-white-album" (slug/slugify "The White Album"))))
  (testing "drops punctuation and collapses runs of separators"
    (is (= "attention-is-all-you-need" (slug/slugify "Attention Is All You Need!")))
    (is (= "hello-world" (slug/slugify "  Hello,   World  "))))
  (testing "blank or nil in, blank out"
    (is (= "" (slug/slugify "")))
    (is (= "" (slug/slugify nil)))))
