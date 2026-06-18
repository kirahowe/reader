(ns reader.papers-test
  (:require [charred.api :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reader.db.crud :as crud]
            [reader.papers :as papers]
            [reader.test-support.setup :refer [with-system]]))

(deftest detect-test
  (testing "arXiv URLs (abs/pdf/html), version suffixes, ar5iv — version stripped"
    (is (= {:kind :arxiv :id "2401.12345"} (papers/detect "https://arxiv.org/abs/2401.12345")))
    (is (= {:kind :arxiv :id "2401.12345"} (papers/detect "arxiv.org/pdf/2401.12345v2")))
    (is (= {:kind :arxiv :id "2401.12345"} (papers/detect "https://arxiv.org/html/2401.12345")))
    (is (= {:kind :arxiv :id "2401.12345"} (papers/detect "https://ar5iv.labs.arxiv.org/html/2401.12345"))))

  (testing "bare arXiv ids, arXiv: prefix, and old-style category ids"
    (is (= {:kind :arxiv :id "2401.12345"}      (papers/detect "2401.12345")))
    (is (= {:kind :arxiv :id "2401.12345"}      (papers/detect "arXiv:2401.12345v3")))
    (is (= {:kind :arxiv :id "hep-th/9901001"}  (papers/detect "https://arxiv.org/abs/hep-th/9901001")))
    (is (= {:kind :arxiv :id "math.GT/0309136"} (papers/detect "math.GT/0309136"))))

  (testing "DOIs — doi.org url and bare, including arXiv's own DOI"
    (is (= {:kind :doi :id "10.1145/3292500.3330701"} (papers/detect "https://doi.org/10.1145/3292500.3330701")))
    (is (= {:kind :doi :id "10.1038/nature12373"}     (papers/detect "10.1038/nature12373")))
    (is (= {:kind :doi :id "10.48550/arxiv.2401.12345"} (papers/detect "https://doi.org/10.48550/arXiv.2401.12345"))))

  (testing "non-paper links and junk are nil"
    (is (nil? (papers/detect "https://example.com/some-article")))
    (is (nil? (papers/detect "just some text")))
    (is (nil? (papers/detect "")))
    (is (nil? (papers/detect nil)))))

(def ^:private orcid "https://orcid.org/0000-0001-0000-0001")
(def ^:private ror "https://ror.org/00njsd438")

(def ^:private work
  "An OpenAlex work (string keys) where both authors share one institution."
  {"id"                      "https://openalex.org/W1"
   "title"                   "Attention Is All You Need"
   "publication_date"        "2017-06-12"
   "abstract_inverted_index" {"We" [0] "propose" [1]}
   "primary_location"        {"source" {"id" "https://openalex.org/S1"
                                        "display_name" "arXiv" "type" "repository"}}
   "authorships"
   [{"author"       {"id" "https://openalex.org/A1" "display_name" "Ashish Vaswani" "orcid" orcid}
     "institutions" [{"id" "https://openalex.org/I1" "display_name" "Google" "ror" ror "country_code" "US"}]}
    {"author"       {"id" "https://openalex.org/A2" "display_name" "Noam Shazeer" "orcid" nil}
     "institutions" [{"id" "https://openalex.org/I1" "display_name" "Google" "ror" ror "country_code" "US"}]}]})

(defn- mk-user [ds email] (:users/id (crud/create! ds :users {:email email})))

(defn- stub-body [url]
  (when (str/starts-with? url "https://arxiv.org/html")
    "<p>body <math><mi>x</mi></math></p>"))

(deftest start-then-extract-builds-the-graph
  (with-system [system]
    (let [ds                         (:reader.db/datasource system)
          uid                        (mk-user ds "p@example.com")
          ref                        {:kind :arxiv :id "1706.03762"}
          {:keys [paper queue-item]} (papers/start! ds uid ref)
          pid                        (:papers/id paper)
          run!                       (fn [body-fetch]
                                       (papers/extract-paper!
                                        ds {:paper-id pid :kind "arxiv" :id "1706.03762"}
                                        {:openalex-fetch (constantly (json/write-json-str work))
                                         :body-fetch     body-fetch}))]
      (testing "start! creates a placeholder, queues it, enqueues one job"
        (is (= "arXiv:1706.03762" (:papers/title paper)))
        (is (= "1706.03762" (:papers/arxiv-id paper)))
        (is (some? queue-item))
        (is (= :importing (papers/status ds pid)))
        (is (= 1 (count (crud/find-many ds :jobs {:queue-name "extract-paper"})))))

      (testing "re-pasting reuses the paper and doesn't double-enqueue"
        (papers/start! ds uid ref)
        (is (= 1 (count (crud/find-many ds :jobs {:queue-name "extract-paper"})))))

      (testing "the job fills the paper metadata + reflowed body"
        (run! stub-body)
        (let [p (crud/by-id ds :papers pid)]
          (is (= "Attention Is All You Need" (:papers/title p)))
          (is (= "We propose" (:papers/abstract p)))
          (is (str/includes? (:papers/body-html p) "<math"))
          (is (= :done (papers/status ds pid)))
          (testing "venue resolved as a preprint affiliation"
            (let [venue (crud/by-id ds :affiliations (:papers/affiliation-id p))]
              (is (= "arXiv" (:affiliations/name venue)))
              (is (= "preprint" (:affiliations/type venue)))))))

      (testing "authors + institutions resolved to canonical entities"
        (let [aships  (crud/find-many ds :authorships {:readable-id pid})
              vaswani (crud/find-1 ds :authors {:orcid orcid})
              links   (crud/find-many ds :author-affiliations {:author-id (:authors/id vaswani)})
              google  (crud/by-id ds :affiliations (:author-affiliations/affiliation-id (first links)))]
          (is (= 2 (count aships)) "one authorship per author, byline order")
          (is (= [0 1] (sort (map :authorships/ordinal aships))))
          (is (= "https://orcid.org/0000-0001-0000-0001" (:authors/orcid vaswani)))
          (is (= 1 (count links)) "linked to one institution")
          (is (= "Google" (:affiliations/name google)))
          (is (= "institution" (:affiliations/type google)))
          (is (= ror (:affiliations/ror google)))
          (is (= 1 (count (crud/find-many ds :affiliations {:ror ror})))
              "the shared institution is upserted once, not per author")))

      (testing "re-running the job is idempotent — authorships replaced, links not duplicated"
        (run! (constantly nil))
        (let [vaswani (crud/find-1 ds :authors {:orcid orcid})]
          (is (= 2 (count (crud/find-many ds :authorships {:readable-id pid}))))
          (is (= 1 (count (crud/find-many ds :author-affiliations {:author-id (:authors/id vaswani)})))))))))
