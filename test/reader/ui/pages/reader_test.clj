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
          html       (reader/show queue-item content [])]
      (is (re-find #"https://example\.test/x" html) "the http(s) link renders")
      (is (not (re-find #"(?i)javascript:" html)) "the javascript: link is dropped")
      (is (re-find #"target=\"_blank\"" html) "external links open in a new tab")
      (is (re-find #"rel=\"noopener noreferrer\"" html) "external links are hardened"))))

(deftest unsubscribe-link-rendering
  (let [reading {:queue-items/id (random-uuid) :queue-items/state "reading"}
        render  (fn [unsub] (reader/show reading {:title "Issue" :unsubscribe-url unsub} []))]
    (testing "an https unsubscribe url renders a hardened, new-tab Unsubscribe link"
      (let [html (render "https://news.test/unsub?u=1")]
        (is (re-find #"Unsubscribe" html))
        (is (re-find #"https://news\.test/unsub" html))
        (is (re-find #"target=\"_blank\"" html))
        (is (re-find #"rel=\"noopener noreferrer\"" html))))
    (testing "a mailto unsubscribe url renders a mailto link"
      (let [html (render "mailto:leave@news.test")]
        (is (re-find #"Unsubscribe" html))
        (is (re-find #"mailto:leave@news\.test" html))))
    (testing "a dangerous scheme is dropped, not rendered"
      (let [html (render "javascript:alert(1)")]
        (is (not (re-find #"(?i)javascript:" html)))
        (is (not (re-find #"Unsubscribe" html)))))
    (testing "no unsubscribe url -> no link"
      (is (not (re-find #"Unsubscribe" (render nil)))))))

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
          html       (reader/show queue-item content [])]
      (is (re-find #"2024-01-15" html) "the date renders as yyyy-MM-dd")
      (is (not (re-find #"10:30" html)) "the time portion is dropped"))))
