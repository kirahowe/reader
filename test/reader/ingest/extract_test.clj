(ns reader.ingest.extract-test
  "Pure extraction tests. `extract` turns a fetched HTML page into a structured
   context: raw metadata signals, deterministic fields (title/date/lang/
   canonical/site-name) with provenance, and a sanitized reader-view body with
   a confidence signal. No network — fixtures are saved pages on the classpath."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reader.ingest.extract :as extract])
  (:import [java.time Instant]))

(defn- fixture [name url]
  (extract/extract (slurp (io/resource (str "reader/ingest/fixtures/" name))) url))

(deftest jsonld-article-test
  (let [{:keys [fields signals body]}
        (fixture "jsonld-article.html"
                 "https://www.example-news.com/2026/05/quiet-revolution-type-design")]

    (testing "title prefers JSON-LD headline"
      (is (= "The Quiet Revolution in Type Design" (get-in fields [:title :value])))
      (is (= :json-ld (get-in fields [:title :source]))))

    (testing "published-at parses the JSON-LD datePublished to an Instant"
      (is (= (Instant/parse "2026-05-14T09:30:00Z") (get-in fields [:published-at :value])))
      (is (= :json-ld (get-in fields [:published-at :source]))))

    (testing "lang comes from the <html lang> attribute"
      (is (= "en" (get-in fields [:lang :value])))
      (is (= :html-lang (get-in fields [:lang :source]))))

    (testing "canonical-url prefers <link rel=canonical> over the utm-tagged og:url"
      (is (= "https://www.example-news.com/2026/05/quiet-revolution-type-design"
             (get-in fields [:canonical-url :value])))
      (is (= :canonical (get-in fields [:canonical-url :source]))))

    (testing "site-name comes from og:site_name"
      (is (= "Example News" (get-in fields [:site-name :value])))
      (is (= :og (get-in fields [:site-name :source]))))

    (testing "raw signals are retained for the entity step"
      (is (= 1 (count (:json-ld signals))))
      (is (= "Example News" (get (:og signals) "og:site_name"))))

    (testing "body keeps the article prose and drops nav + footer boilerplate"
      (is (str/includes? (:text body) "typeface design"))
      (is (str/includes? (:text body) "institutional resistance"))
      (is (not (str/includes? (:text body) "All rights reserved")) "footer stripped")
      (is (not (str/includes? (:text body) "Culture Subscribe")) "nav stripped"))

    (testing "word-count is plausible and reading-time follows the 238 wpm formula"
      (is (< 60 (:word-count body) 200))
      (is (= (long (Math/ceil (* (/ (:word-count body) 238.0) 60)))
             (:reading-time-secs body)))
      (is (= :readability4j (:extractor body))))

    (testing "a clean, content-rich page scores high confidence"
      (is (> (:confidence body) 0.5))
      (is (contains? (:signals body) :link-density))
      (is (contains? (:signals body) :body-page-ratio)))))

(deftest og-blog-test
  (let [{:keys [fields signals body]}
        (fixture "og-blog.html" "https://fieldnotes.example/on-writing-slowly")]

    (testing "title falls back to og:title when there is no JSON-LD"
      (is (= "On Writing Slowly" (get-in fields [:title :value])))
      (is (= :og (get-in fields [:title :source]))))

    (testing "canonical-url uses og:url when there is no <link rel=canonical>"
      (is (= "https://fieldnotes.example/on-writing-slowly" (get-in fields [:canonical-url :value])))
      (is (= :og (get-in fields [:canonical-url :source]))))

    (testing "published-at parses article:published_time"
      (is (= (Instant/parse "2026-03-02T00:00:00Z") (get-in fields [:published-at :value]))))

    (testing "site-name from og:site_name"
      (is (= "Field Notes" (get-in fields [:site-name :value]))))

    (testing "lang is absent (no html lang, no JSON-LD)"
      (is (nil? (get-in fields [:lang :value]))))

    (testing "the <meta name=author> byline is captured in signals for the entity step"
      (is (= "Lena Ortiz" (get (:meta signals) "author"))))

    (testing "body keeps prose and drops nav + aside"
      (is (str/includes? (:text body) "tempo of thought"))
      (is (not (str/includes? (:text body) "Related posts")))
      (is (not (str/includes? (:text body) "About Archive RSS"))))))

(deftest bare-page-test
  (let [{:keys [fields signals body]}
        (fixture "bare.html" "https://notes.example/x")]

    (testing "title falls back to the <title> tag"
      (is (= "untitled" (get-in fields [:title :value])))
      (is (= :title-tag (get-in fields [:title :source]))))

    (testing "no date is found"
      (is (nil? (get-in fields [:published-at :value]))))

    (testing "site-name falls back to the registrable domain of the url"
      (is (= "notes.example" (get-in fields [:site-name :value])))
      (is (= :domain (get-in fields [:site-name :source]))))

    (testing "canonical-url falls back to the fetched url"
      (is (= "https://notes.example/x" (get-in fields [:canonical-url :value])))
      (is (= :url (get-in fields [:canonical-url :source]))))

    (testing "no JSON-LD on the page"
      (is (empty? (:json-ld signals))))

    (testing "a near-empty page scores low confidence — the Tier-2 escalation signal"
      (is (< (:confidence body) 0.5)))))

(deftest ld-text-test
  (testing "a bare string is trimmed"
    (is (= "Jane Smith" (extract/ld-text "  Jane Smith  "))))
  (testing "a node resolves via name; a value object via @value"
    (is (= "Jane Smith" (extract/ld-text {"@type" "Person" "name" "Jane Smith"})))
    (is (= "Jane Smith" (extract/ld-text {"@value" "Jane Smith" "@language" "en"}))))
  (testing "a language-tagged name nested in a node resolves (the former crash case)"
    (is (= "Jane Smith" (extract/ld-text {"name" {"@value" "Jane Smith" "@language" "en"}}))))
  (testing "an array yields the first resolvable value, preserving order"
    (is (= "Ada" (extract/ld-text ["Ada" "Babbage"])))
    (is (= "Ada" (extract/ld-text [{"name" "Ada"} {"name" "Babbage"}]))))
  (testing "non-textual or blank input is nil, never an exception"
    (is (nil? (extract/ld-text 123)))
    (is (nil? (extract/ld-text {})))
    (is (nil? (extract/ld-text {"name" 123})))
    (is (nil? (extract/ld-text nil)))
    (is (nil? (extract/ld-text "   ")))))

(deftest parse-temporal-test
  (testing "full instant, offset datetime, and date-only all normalize to Instant"
    (is (= (Instant/parse "2026-05-14T09:30:00Z") (extract/parse-temporal "2026-05-14T09:30:00Z")))
    (is (= (Instant/parse "2026-05-14T07:30:00Z") (extract/parse-temporal "2026-05-14T09:30:00+02:00")))
    (is (= (Instant/parse "2026-03-02T00:00:00Z") (extract/parse-temporal "2026-03-02"))))
  (testing "garbage is nil, not an exception"
    (is (nil? (extract/parse-temporal "not a date")))
    (is (nil? (extract/parse-temporal nil)))))

(deftest sanitizes-body-html-test
  (testing "scripts and event-handler attributes are stripped from the stored body"
    (let [html (str "<html><head><title>x</title></head><body><article>"
                    "<h1>Heading</h1>"
                    "<p>A sufficiently long paragraph of genuine article content so that the "
                    "readability pass treats this block as the main body and returns it intact "
                    "for sanitization rather than discarding it as too short to matter.</p>"
                    "<p onclick=\"steal()\">Another real paragraph with enough words to count as "
                    "part of the main content block of this little test document here.</p>"
                    "<script>alert('xss')</script>"
                    "</article></body></html>")
          {:keys [body]} (extract/extract html "https://x.test/a")]
      (is (not (str/includes? (:html body) "<script")))
      (is (not (str/includes? (:html body) "onclick")))
      (is (str/includes? (:text body) "genuine article content")))))
