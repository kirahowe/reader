(ns reader.eval.inspect-test
  "Drill-down assembly for the evals app, against a real embedded Postgres: seed a
   readable with a tagging attempt + a stored baseline, then assert the per-case
   view stitches the event, the model's proposed labels, the assigned tags, and
   the content the model saw."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.db.crud :as crud]
            [reader.domain.tags :as tags]
            [reader.eval.inspect :as inspect]
            [reader.ingest.tag-events :as tag-events]
            [reader.test-support.setup :refer [with-system]]))

(defn- seed-tagged-article!
  "An article with two baseline tags and one done tagging attempt whose
   provenance proposed a third (deduped-away) label. Returns the article id."
  [ds]
  (let [aid (:articles/id
             (crud/create! ds :articles
                           {:title         "Attention Is All You Need"
                            :slug          "attention-is-all-you-need"
                            :canonical-url "https://example.com/attention"
                            :abstract      "We propose the Transformer, a model architecture..."
                            :word-count    4200}))
        ml  (:tags/id (crud/create! ds :tags {:slug "machine-learning" :label "machine learning"}))
        nlp (:tags/id (crud/create! ds :tags {:slug "nlp" :label "nlp"}))]
    (tags/set-baseline! ds "article" aid [{:tag-id ml :confidence 0.95}
                                          {:tag-id nlp :confidence 0.80}])
    (tag-events/record! ds {:readable-type "article" :readable-id aid
                            :outcome :done :model "gpt-4o-mini" :tag-count 3
                            :duration-ms 880
                            :provenance {:labels ["machine learning" "nlp" "transformers"]
                                         :vocab-size 12}})
    aid))

(deftest tagging-case-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          aid (seed-tagged-article! ds)
          c   (inspect/tagging-case ds "article" aid)]
      (testing "stitches the latest attempt's event"
        (is (= "done" (get-in c [:event :outcome])))
        (is (= "gpt-4o-mini" (get-in c [:event :model])))
        (is (= 880 (get-in c [:event :duration-ms]))))
      (testing "surfaces the model's proposed labels + vocab size from provenance"
        (is (= ["machine learning" "nlp" "transformers"] (:proposed c)))
        (is (= 12 (:vocab-size c))))
      (testing "lists the assigned baseline tags with confidence, by label"
        (is (= [{:label "machine learning" :confidence 0.95}
                {:label "nlp" :confidence 0.8}]
               (:assigned c))))
      (testing "includes the content the tagger saw"
        (is (= "Attention Is All You Need" (get-in c [:readable :title])))
        (is (= 4200 (get-in c [:readable :word-count])))))))

(deftest tagging-cases-list-test
  (with-system [system]
    (let [ds         (:reader.db/datasource system)
          aid        (seed-tagged-article! ds)
          [row & more] (inspect/tagging-cases ds {:limit 50})]
      (is (empty? more) "one attempt seeded")
      (is (= aid (:readable-id row)))
      (is (= "Attention Is All You Need" (:title row)))
      (is (= "done" (:outcome row)))
      (is (= 3 (:tag-count row))))))

(defn- seed-extracted-article!
  "An article with a two-author byline + a source, plus one done extraction
   event keyed on its canonical url. Returns that url."
  [ds]
  (let [url "https://www.example-news.com/scoop"
        aff (:affiliations/id (crud/create! ds :affiliations
                                            {:name "Example News" :slug "example-news" :type "newspaper"}))
        a1  (:authors/id (crud/create! ds :authors {:name "Jane Roe" :slug "jane-roe"}))
        a2  (:authors/id (crud/create! ds :authors {:name "John Doe" :slug "john-doe"}))
        aid (:articles/id (crud/create! ds :articles
                                        {:title "The Scoop" :slug "the-scoop"
                                         :canonical-url url :affiliation-id aff :word-count 1200}))]
    (crud/create! ds :authorships {:author-id a1 :readable-type "article" :readable-id aid :ordinal 0})
    (crud/create! ds :authorships {:author-id a2 :readable-type "article" :readable-id aid :ordinal 1})
    (crud/create! ds :extraction-events
                  {:url url :domain "example-news.com" :outcome "done"
                   :extractor "readability4j" :word-count 1200
                   :body-confidence 0.85 :entity-confidence 0.9 :author-count 2
                   :title-source "json-ld" :author-source "json-ld"
                   :affiliation-source "og" :published-source "json-ld"
                   :fetch-ms 30 :extract-ms 40
                   :provenance {:fields       {:title {:source "json-ld"}}
                                :body-signals {:link-density 0.1}}})
    url))

(deftest extraction-case-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          url (seed-extracted-article! ds)
          c   (inspect/extraction-case ds url)]
      (testing "stitches the latest attempt's first-class signals"
        (is (= "done" (get-in c [:event :outcome])))
        (is (= 0.85 (get-in c [:event :body-confidence])))
        (is (= 2 (get-in c [:event :author-count])))
        (is (= "json-ld" (get-in c [:event :author-source]))))
      (testing "parses the per-field provenance bag"
        (is (= "json-ld" (get-in c [:provenance :fields :title :source])))
        (is (= 0.1 (get-in c [:provenance :body-signals :link-density]))))
      (testing "resolves the byline in order"
        (is (= ["Jane Roe" "John Doe"] (mapv :name (:authors c)))))
      (testing "resolves the source it was published in"
        (is (= "Example News" (get-in c [:affiliation :name]))))
      (testing "includes the content extracted"
        (is (= "The Scoop" (get-in c [:article :title])))))))

(deftest extraction-cases-list-test
  (with-system [system]
    (let [ds           (:reader.db/datasource system)
          url          (seed-extracted-article! ds)
          [row & more] (inspect/extraction-cases ds {:limit 50})]
      (is (empty? more) "one attempt seeded")
      (is (= url (:url row)))
      (is (= "example-news.com" (:domain row)))
      (is (= "done" (:outcome row)))
      (is (= 2 (:author-count row))))))
