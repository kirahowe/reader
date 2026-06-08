(ns reader.ui.pages.reader-test
  (:require [clojure.test :refer [deftest is testing]]
            [reader.ui.pages.reader :as reader])
  (:import [java.time Instant]))

(deftest external-links-reject-non-http-schemes
  (testing "only http(s) external links render; other schemes are dropped"
    (let [queue-item {:queue-items/id    (random-uuid)
                      :queue-items/state "reading"}
          content    {:title   "A Piece"
                      :authors nil
                      :source  nil
                      :date    nil
                      :body    nil
                      :links   [{:label "Evil" :href "javascript:alert(1)"}
                                {:label "Original" :href "https://example.test/x"}]}
          html       (reader/show queue-item content)]
      (is (re-find #"https://example\.test/x" html) "the http(s) link renders")
      (is (not (re-find #"(?i)javascript:" html)) "the javascript: link is dropped")
      (is (re-find #"target=\"_blank\"" html) "external links open in a new tab")
      (is (re-find #"rel=\"noopener noreferrer\"" html) "external links are hardened"))))

(deftest date-renders-as-day-without-time
  (testing "an Instant date renders as yyyy-MM-dd, dropping the time portion"
    (let [queue-item {:queue-items/id    (random-uuid)
                      :queue-items/state "reading"}
          content    {:title   "A Piece"
                      :authors nil
                      :source  nil
                      :date    (Instant/parse "2024-01-15T10:30:00Z")
                      :body    nil
                      :links   nil}
          html       (reader/show queue-item content)]
      (is (re-find #"2024-01-15" html) "the date renders as yyyy-MM-dd")
      (is (not (re-find #"10:30" html)) "the time portion is dropped"))))
