(ns reader.eval.scoring-test
  (:require [clojure.test :refer [deftest is testing]]
            [reader.eval.scoring :as scoring]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-9))

(deftest prf-test
  (testing "one case: 2 of 3 predicted are correct, 1 golden missed"
    (let [{:keys [precision recall f1 tp fp fn]}
          (scoring/prf [{:golden #{"a" "b" "c"} :predicted #{"a" "b" "d"}}])]
      (is (= [2 1 1] [tp fp fn]))
      (is (close? 2/3 precision))
      (is (close? 2/3 recall))
      (is (close? 2/3 f1))))
  (testing "micro-average pools counts across cases"
    ;; case 1: tp1 fp0 fn1 · case 2: tp1 fp1 fn0 → tp2 fp1 fn1
    (let [{:keys [precision recall]}
          (scoring/prf [{:golden #{"a" "b"} :predicted #{"a"}}
                        {:golden #{"x"} :predicted #{"x" "y"}}])]
      (is (close? 2/3 precision))
      (is (close? 2/3 recall))))
  (testing "perfect prediction"
    (let [{:keys [precision recall f1]} (scoring/prf [{:golden #{"a"} :predicted #{"a"}}])]
      (is (= [1.0 1.0 1.0] [precision recall f1]))))
  (testing "no predictions, non-empty golden → zero, no divide error"
    (let [{:keys [precision recall f1]} (scoring/prf [{:golden #{"a"} :predicted #{}}])]
      (is (= [0.0 0.0 0.0] [precision recall f1])))))

(deftest accuracy-test
  (let [{:keys [accuracy correct n]}
        (scoring/accuracy [{:golden "The New Yorker" :predicted "The New Yorker"}
                           {:golden "Wired" :predicted nil}
                           {:golden "MIT" :predicted "MIT"}])]
    (is (= 2 correct))
    (is (= 3 n))
    (is (close? 2/3 accuracy))))
