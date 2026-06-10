(ns reader.ingest.entities-test
  "Tests for the deterministic entity seam. Contexts are produced by the real
   extractor over the saved fixtures, so this also proves extract -> entities
   compose, plus hand-built contexts for the byline/URL edge cases and the
   EntityResult contract caps."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [reader.ingest.entities :as entities]
            [reader.ingest.extract :as extract]
            [reader.ingest.schema :as schema]))

(defn- ctx [name url]
  (extract/extract (slurp (io/resource (str "reader/ingest/fixtures/" name))) url))

(deftest jsonld-entities-test
  (let [{:keys [authors affiliation overall-confidence] :as result}
        (entities/from-metadata (ctx "jsonld-article.html" "https://www.example-news.com/x"))]
    (testing "authors come from JSON-LD, in byline order, tagged with provenance"
      (is (= ["Mara Whitfield" "Devon Park"] (mapv :name authors)))
      (is (every? #(= :json-ld (:source %)) authors)))
    (testing "affiliation is the publication, from og:site_name"
      (is (= "Example News" (:name affiliation)))
      (is (= :og (:source affiliation))))
    (testing "high overall confidence, and the result satisfies EntityResult"
      (is (> overall-confidence 0.9))
      (is (m/validate schema/EntityResult result)))))

(deftest og-blog-entities-test
  (let [{:keys [authors affiliation]}
        (entities/from-metadata (ctx "og-blog.html" "https://fieldnotes.example/on-writing-slowly"))]
    (testing "author falls back to the <meta> byline when there is no JSON-LD"
      (is (= ["Lena Ortiz"] (mapv :name authors)))
      (is (= :meta (:source (first authors)))))
    (testing "affiliation from og:site_name"
      (is (= "Field Notes" (:name affiliation))))))

(deftest bare-entities-test
  (let [{:keys [authors affiliation overall-confidence] :as result}
        (entities/from-metadata (ctx "bare.html" "https://notes.example/x"))]
    (testing "no authors when the page declares none"
      (is (= [] authors)))
    (testing "affiliation falls back to the domain"
      (is (= "notes.example" (:name affiliation)))
      (is (= :domain (:source affiliation))))
    (testing "still a valid EntityResult; confidence reflects the lone weak signal"
      (is (m/validate schema/EntityResult result))
      (is (= 0.5 overall-confidence)))))

(deftest byline-and-url-author-test
  (testing "a multi-name meta byline splits into separate authors"
    (let [c {:url     "https://x/a"
             :signals {:json-ld [] :og {} :meta {"author" "By Ada Lovelace and Charles Babbage"}}
             :fields  {:site-name {:value "X" :source :og}}
             :body    {}}
          {:keys [authors]} (entities/from-metadata c)]
      (is (= ["Ada Lovelace" "Charles Babbage"] (mapv :name authors)))
      (is (every? #(= :meta (:source %)) authors))))
  (testing "an og:article:author that is a profile URL is not mistaken for a name"
    (let [c {:url     "https://x/a"
             :signals {:json-ld [] :og {"article:author" "https://x.test/author/jane"} :meta {}}
             :fields  {:site-name {:value "X" :source :og}}
             :body    {}}]
      (is (= [] (:authors (entities/from-metadata c)))))))

(deftest jsonld-structured-author-name-test
  (testing "a JSON-LD author whose name is a language-tagged value object resolves, not crashes"
    (let [c {:url     "https://x/a"
             :signals {:json-ld [{"@type" "Article"
                                  "author" {"@type" "Person"
                                            "name"  {"@value" "Grace Hopper" "@language" "en"}}}]
                       :og {} :meta {}}
             :fields  {:site-name {:value "X" :source :og}}
             :body    {}}
          {:keys [authors]} (entities/from-metadata c)]
      (is (= ["Grace Hopper"] (mapv :name authors)))
      (is (= :json-ld (:source (first authors)))))))

(deftest jsonld-author-url-test
  (testing "a named JSON-LD author's url is captured; sameAs is the fallback"
    (let [c {:url     "https://x/a"
             :signals {:json-ld [{"@type"  "Article"
                                  "author" [{"@type" "Person" "name" "Ada Lovelace"
                                             "url"   "https://ada.example"}
                                            {"@type" "Person" "name" "Charles Babbage"
                                             "sameAs" ["https://charles.example" "https://x.test/cb"]}]}]
                       :og {} :meta {}}
             :fields  {:site-name {:value "X" :source :og}}
             :body    {}}
          {:keys [authors]} (entities/from-metadata c)]
      (is (= ["Ada Lovelace" "Charles Babbage"] (mapv :name authors)))
      (is (= "https://ada.example" (:url (first authors))))
      (is (= "https://charles.example" (:url (second authors))))))
  (testing "a meta byline author has no url"
    (let [c {:url     "https://x/a"
             :signals {:json-ld [] :og {} :meta {"author" "Lena Ortiz"}}
             :fields  {:site-name {:value "X" :source :og}}
             :body    {}}]
      (is (not (contains? (first (:authors (entities/from-metadata c))) :url))))))

(deftest extract-output-conforms-to-context-test
  (testing "the extractor's output satisfies the ExtractionContext contract"
    (is (m/validate schema/ExtractionContext
                    (ctx "jsonld-article.html" "https://www.example-news.com/x")))))

(deftest entityresult-caps-test
  (testing "the contract rejects adversarial output: too many authors / over-long name"
    (is (not (m/validate schema/EntityResult
                         {:authors            (vec (repeat 51 {:name "x" :source :llm :confidence 0.5}))
                          :affiliation        nil
                          :overall-confidence 0.5})))
    (is (not (m/validate schema/EntityResult
                         {:authors            [{:name (apply str (repeat 201 "x")) :source :llm :confidence 0.5}]
                          :affiliation        nil
                          :overall-confidence 0.5})))))
