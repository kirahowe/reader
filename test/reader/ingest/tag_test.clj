(ns reader.ingest.tag-test
  "Tests for the infer-tags abstraction: the TagResult boundary (coerce/validate),
   the pure prompt + parse helpers, and the LLM implementation over a stub completion fn."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reader.ingest.tag :as tag]))

(deftest coerce-test
  (testing "clamps count, lowercases + trims labels, drops blanks, clamps confidence"
    (let [out (tag/coerce {:tags (into [{:label "  Machine Learning " :confidence 2.0}
                                        {:label "" :confidence 0.4}
                                        {:label "Rust" :confidence -1.0}]
                                       (map (fn [i] {:label (str "extra-" i) :confidence 0.5}))
                                       (range 20))})]
      (is (= "machine learning" (:label (first (:tags out)))) "trimmed + lowercased")
      (is (= 1.0 (:confidence (first (:tags out)))) "confidence clamped to 1.0")
      (is (every? #(<= 0.0 (:confidence %) 1.0) (:tags out)))
      (is (not-any? #(str/blank? (:label %)) (:tags out)) "blank label dropped")
      (is (<= (count (:tags out)) 12) "capped at 12 tags")))
  (testing "the coerced result satisfies the TagResult contract"
    (is (tag/valid? (tag/coerce {:tags [{:label "go" :confidence 0.9}] :model "m"})))))

(deftest valid?-test
  (testing "rejects out-of-contract output"
    (is (not (tag/valid? {:tags [{:label "ok" :confidence 5.0}]})) "confidence out of range")
    (is (not (tag/valid? {:tags [{:label "" :confidence 0.5}]})) "empty label")))

(deftest build-messages-test
  (let [msgs (tag/build-messages {:title "Deep RL" :abstract "We study agents." :text "body"}
                                 ["machine-learning" "robotics"])]
    (testing "a system message inlines the existing vocabulary"
      (is (= "system" (:role (first msgs))))
      (is (str/includes? (:content (first msgs)) "machine-learning, robotics")))
    (testing "the user message carries the title and abstract"
      (is (= "user" (:role (second msgs))))
      (is (str/includes? (:content (second msgs)) "Deep RL"))
      (is (str/includes? (:content (second msgs)) "We study agents.")))))

(deftest parse-test
  (testing "parses a clean JSON object"
    (is (= [{:label "ml" :confidence 0.9}]
           (tag/parse "{\"tags\":[{\"label\":\"ml\",\"confidence\":0.9}]}"))))
  (testing "tolerates markdown fences and surrounding prose"
    (is (= [{:label "rust" :confidence 0.8}]
           (tag/parse "Here you go:\n```json\n{\"tags\":[{\"label\":\"rust\",\"confidence\":0.8}]}\n```"))))
  (testing "a parsed object with an empty tag list is [] — the model saw nothing to tag"
    (is (= [] (tag/parse "{\"tags\":[]}"))))
  (testing "nil — not [] — when there is no parseable tag object (a retryable model failure)"
    (is (nil? (tag/parse "sorry, I can't")))
    (is (nil? (tag/parse "{\"oops\":1}")) "an object without :tags is not a valid result")
    (is (nil? (tag/parse "{not json at all}")) "malformed JSON inside the braces")))

(deftest llm-tagger-test
  (let [seen     (atom nil)
        complete (fn [messages _opts] (reset! seen messages)
                   "{\"tags\":[{\"label\":\"Databases\",\"confidence\":0.9}]}")
        infer    (tag/llm-tagger complete "gpt-test")
        result   (tag/coerce (infer {:title "On B-Trees"} ["databases"]))]
    (testing "calls the completion fn with the built messages and parses + records the model"
      (is (some? @seen))
      (is (= "gpt-test" (:model (infer {:title "x"} []))))
      (is (= [{:label "databases" :confidence 0.9}] (:tags result)))
      (is (tag/valid? result)))))

(deftest llm-tagger-response-format-test
  (let [seen     (atom nil)
        complete (fn [_messages opts] (reset! seen opts) "{\"tags\":[]}")
        infer    (tag/llm-tagger complete "gpt-test" :json-schema)]
    (testing "threads a strict Structured Outputs descriptor through to the completion fn"
      (infer {:title "x"} [])
      (is (= :json-schema (get-in @seen [:response-format :mode])))
      (is (= "tags" (get-in @seen [:response-format :name])))
      (is (map? (get-in @seen [:response-format :schema]))))))

(deftest llm-tagger-unparseable-test
  (let [infer (tag/llm-tagger (fn [_messages _opts] "the model rambled, no JSON here") "gpt-test")]
    (testing "an unparseable response is a retryable error, not a silent zero-tag success"
      (let [ex (try (infer {:title "x"} []) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= :unparseable-tags (:error-class (ex-data ex))))
        (is (not (:fatal? (ex-data ex))) "retryable — the worker should retry, not give up")))))

(deftest stub-tags-test
  (testing "derives a couple of meaningful tags from the title, skipping stopwords"
    (is (= [{:label "rust" :confidence 0.5} {:label "ownership" :confidence 0.5}]
           (tag/stub-tags {:title "What is Rust and ownership"})))))
