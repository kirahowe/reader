(ns reader.eval.labels-test
  "Feedback collection + scoring against a real embedded Postgres (reader + eval
   schemas): record a corrected golden label, then assert the score reflects the
   gap between what the pipeline produced and that golden truth."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.db.crud :as crud]
            [reader.domain.tags :as tags]
            [reader.eval.labels :as labels]
            [reader.eval.test-support :refer [with-eval-system]]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-9))

(deftest apply-corrections-test
  (testing "drops flagged-wrong slugs and adds missing ones"
    (is (= #{"machine-learning" "databases"}
           (labels/apply-corrections #{"machine-learning" "nlp"} #{"nlp"} #{"databases"}))))
  (testing "confirmed (nothing flagged or added) keeps production as-is"
    (is (= #{"a" "b"} (labels/apply-corrections #{"a" "b"} #{} #{})))))

(deftest tagging-label-and-score-test
  (with-eval-system [system]
    (let [ds  (:reader.db/datasource system)
          aid (:articles/id (crud/create! ds :articles
                                          {:title "Attention" :slug "attention"
                                           :canonical-url "https://example.com/a"}))
          ml  (:tags/id (crud/create! ds :tags {:slug "machine-learning" :label "machine learning"}))
          nlp (:tags/id (crud/create! ds :tags {:slug "nlp" :label "nlp"}))]
      ;; production baseline assigns {machine-learning, nlp}
      (tags/set-baseline! ds "article" aid [{:tag-id ml} {:tag-id nlp}])
      ;; operator: nlp is wrong, databases is missing → golden {machine-learning, databases}
      (labels/record-tagging! ds {:readable-type "article" :readable-id aid
                                  :golden (labels/apply-corrections #{"machine-learning" "nlp"}
                                                                    #{"nlp"} #{"databases"})
                                  :labeled-by "op@x.test"})
      (testing "scores current production against the golden set"
        (let [{:keys [labeled tp fp fn precision recall]} (labels/tagging-score ds)]
          ;; predicted {ml,nlp} vs golden {ml,databases}: tp ml, fp nlp, fn databases
          (is (= 1 labeled))
          (is (= [1 1 1] [tp fp fn]))
          (is (close? 0.5 precision))
          (is (close? 0.5 recall))))
      (testing "re-labeling the same readable updates, not duplicates"
        (labels/record-tagging! ds {:readable-type "article" :readable-id aid
                                    :golden #{"machine-learning" "nlp"} :labeled-by "op@x.test"})
        (let [{:keys [labeled precision recall]} (labels/tagging-score ds)]
          (is (= 1 labeled) "still one label for the readable")
          (is (close? 1.0 precision) "golden now matches production exactly")
          (is (close? 1.0 recall)))))))

(deftest extraction-label-and-score-test
  (with-eval-system [system]
    (let [ds  (:reader.db/datasource system)
          url "https://example.com/scoop"
          aff (:affiliations/id (crud/create! ds :affiliations
                                              {:name "Example News" :slug "example-news" :type "newspaper"}))
          a1  (:authors/id (crud/create! ds :authors {:name "Jane Roe" :slug "jane-roe"}))
          a2  (:authors/id (crud/create! ds :authors {:name "John Doe" :slug "john-doe"}))
          aid (:articles/id (crud/create! ds :articles
                                          {:title "Scoop" :slug "scoop"
                                           :canonical-url url :affiliation-id aff}))]
      (crud/create! ds :authorships {:author-id a1 :readable-type "article" :readable-id aid :ordinal 0})
      (crud/create! ds :authorships {:author-id a2 :readable-type "article" :readable-id aid :ordinal 1})
      ;; production byline {jane-roe, john-doe}, source example-news.
      ;; operator: only jane-roe is right (john-doe wrong); source is correct.
      (labels/record-extraction! ds {:subject-url url :authors #{"jane-roe"}
                                     :source "example-news" :labeled-by "op@x.test"})
      (let [{:keys [byline source]} (labels/extraction-score ds)]
        (testing "byline precision drops for the spurious author, recall stays perfect"
          (is (= 1 (:labeled byline)))
          (is (close? 0.5 (:precision byline)))
          (is (close? 1.0 (:recall byline))))
        (testing "source matches production → accurate"
          (is (close? 1.0 (:accuracy source))))))))
