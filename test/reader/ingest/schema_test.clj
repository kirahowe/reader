(ns reader.ingest.schema-test
  "Tests for the EntityResult boundary guard. coerce-entities is what makes the
   contract's caps actually bind on the write path (not just in tests), for any
   seam implementation including a future untrusted LLM one."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.ingest.schema :as schema]))

(deftest coerce-entities-test
  (testing "an over-long author name is truncated to the 200-char cap"
    (let [{:keys [authors]} (schema/coerce-entities
                             {:authors            [{:name (apply str (repeat 250 "x")) :source :llm :confidence 0.5}]
                              :affiliation        nil
                              :overall-confidence 0.5})]
      (is (= 200 (count (:name (first authors)))))))

  (testing "more than 50 authors are capped to 50"
    (let [{:keys [authors]} (schema/coerce-entities
                             {:authors            (vec (repeat 60 {:name "x" :source :llm :confidence 0.5}))
                              :affiliation        nil
                              :overall-confidence 0.5})]
      (is (= 50 (count authors)))))

  (testing "blank-named authors are dropped"
    (let [{:keys [authors]} (schema/coerce-entities
                             {:authors            [{:name "  " :source :llm :confidence 0.5}
                                                   {:name "Ada" :source :llm :confidence 0.5}]
                              :affiliation        nil
                              :overall-confidence 0.5})]
      (is (= ["Ada"] (mapv :name authors)))))

  (testing "an over-long affiliation name is truncated; a nil affiliation stays nil"
    (is (= 200 (count (:name (:affiliation (schema/coerce-entities
                                            {:authors            []
                                             :affiliation        {:name (apply str (repeat 250 "y")) :source :llm :confidence 0.5}
                                             :overall-confidence 0.0}))))))
    (is (nil? (:affiliation (schema/coerce-entities
                             {:authors [] :affiliation nil :overall-confidence 0.0})))))

  (testing "adversarial input is clamped into a valid EntityResult"
    (is (schema/valid-entities?
         (schema/coerce-entities
          {:authors            (vec (repeat 60 {:name (apply str (repeat 250 "x")) :source :llm :confidence 0.5}))
           :affiliation        nil
           :overall-confidence 0.5})))))
