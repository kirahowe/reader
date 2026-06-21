(ns reader.ai-test
  "Tests for the pluggable model clients. The HTTP edge (reader.http/post!) is
   stubbed so we exercise the request shaping and response/error handling, not
   the network."
  (:require [charred.api :as json]
            [clojure.test :refer [deftest is testing]]
            [reader.ai :as ai]
            [reader.http :as http]))

(defn- responding
  "A reader.http/post! stand-in that records the call into `calls` and delivers
   `response` (a {:status :body :error} map)."
  [calls response]
  (fn [url body _opts]
    (swap! calls conj {:url url :body body})
    (doto (promise) (deliver response))))

(defn- ok [data] {:status 200 :body (json/write-json-str data) :error nil})

(deftest complete-test
  (let [calls (atom [])]
    (with-redefs [http/post! (responding calls (ok {:choices [{:message {:content "ml, rust"}}]}))]
      (let [out (ai/complete {:api-url "http://m" :api-key "k" :model "gpt-x"}
                             [{:role "user" :content "tag this"}])]
        (testing "returns the assistant message content"
          (is (= "ml, rust" out)))
        (testing "posts to the chat-completions endpoint with the model"
          (is (= "http://m/chat/completions" (:url (first @calls))))
          (is (= "gpt-x" (:model (:body (first @calls))))))))))

(deftest embed-test
  (let [calls (atom [])]
    (with-redefs [http/post! (responding calls (ok {:data [{:index 1 :embedding [0.0 1.0]}
                                                           {:index 0 :embedding [1.0 0.0]}]}))]
      (testing "returns one float vector per input, ordered by the response index"
        (is (= [[1.0 0.0] [0.0 1.0]]
               (ai/embed {:api-url "http://m" :api-key "k" :model "embed-x"} ["a" "b"]))))
      (testing "a single string is wrapped to a one-element input list"
        (ai/embed {:api-url "http://m" :model "embed-x"} "solo")
        (is (= ["solo"] (:input (:body (last @calls))))))
      (testing "passes :dimensions through when set"
        (ai/embed {:api-url "http://m" :model "embed-x" :dimensions 256} "x")
        (is (= 256 (:dimensions (:body (last @calls)))))))))

(deftest error-mapping-test
  (testing "a non-2xx response throws a retryable model-status error"
    (with-redefs [http/post! (fn [& _] (doto (promise) (deliver {:status 500 :body "boom"})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-2xx"
                            (ai/complete {:api-url "http://m" :model "x"} [])))))
  (testing "401 is fatal — a bad key won't fix itself on retry"
    (with-redefs [http/post! (fn [& _] (doto (promise) (deliver {:status 401 :body "no"})))]
      (is (try (ai/embed {:api-url "http://m" :model "x"} "y")
               false
               (catch clojure.lang.ExceptionInfo e
                 (and (= :model-auth (:error-class (ex-data e)))
                      (true? (:fatal? (ex-data e)))))))))
  (testing "a transport error surfaces as model-transport"
    (with-redefs [http/post! (fn [& _] (doto (promise) (deliver {:error (Exception. "conn")})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"model request failed"
                            (ai/complete {:api-url "http://m" :model "x"} []))))))
