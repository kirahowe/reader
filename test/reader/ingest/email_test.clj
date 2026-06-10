(ns reader.ingest.email-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reader.ingest.email :as email])
  (:import (java.time Instant)))

(def raw-eml
  (str/join "\r\n"
            ["From: Ben Thompson <ben@stratechery.com>"
             "To: r-abc@inbox.reader.test"
             "Subject: Weekly Update"
             "Message-ID: <issue-42@stratechery.com>"
             "Date: Mon, 8 Jun 2026 12:00:00 +0000"
             "MIME-Version: 1.0"
             "Content-Type: multipart/alternative; boundary=\"BOUND\""
             ""
             "--BOUND"
             "Content-Type: text/plain; charset=UTF-8"
             ""
             "Plain text fallback."
             ""
             "--BOUND"
             "Content-Type: text/html; charset=UTF-8"
             ""
             "<html><body><h1>Hello</h1><p>World &amp; friends</p><script>alert('x')</script></body></html>"
             ""
             "--BOUND--"
             ""]))

(deftest parse-multipart-alternative
  (let [{:keys [subject from-name from-email sent-at message-id body-html]}
        (email/parse (.getBytes raw-eml "UTF-8"))]
    (testing "decodes the headers"
      (is (= "Weekly Update" subject))
      (is (= "Ben Thompson" from-name))
      (is (= "ben@stratechery.com" from-email))
      (is (= "<issue-42@stratechery.com>" message-id))
      (is (= (Instant/parse "2026-06-08T12:00:00Z") sent-at)))
    (testing "prefers the html alternative and sanitizes it"
      (is (str/includes? body-html "<h1>Hello</h1>"))
      (is (str/includes? body-html "World"))
      (is (not (str/includes? body-html "alert")) "the script is stripped")
      (is (not (str/includes? body-html "<script"))))))

(deftest parse-plain-only-falls-back
  (let [raw (str/join "\r\n"
                      ["From: News <hello@example.com>"
                       "Subject: Plain"
                       "Message-ID: <p1@example.com>"
                       "Content-Type: text/plain; charset=UTF-8"
                       ""
                       "Just text <not a tag>."
                       ""])
        {:keys [body-html]} (email/parse (.getBytes raw "UTF-8"))]
    (testing "a text/plain-only email is wrapped and escaped"
      (is (str/includes? body-html "Just text"))
      (is (not (str/includes? body-html "<not a tag>")) "angle brackets escaped, not left as markup"))))
