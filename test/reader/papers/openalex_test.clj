(ns reader.papers.openalex-test
  (:require [charred.api :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reader.papers.openalex :as openalex])
  (:import (java.time Instant)))

(def ^:private work
  {"id"                      "https://openalex.org/W123"
   "title"                   "Attention Is All You Need"
   "publication_date"        "2017-06-12"
   "abstract_inverted_index" {"The" [0] "dominant" [1] "models" [2]}
   "primary_location"        {"source" {"id" "https://openalex.org/S456"
                                        "display_name" "arXiv" "type" "repository"}}
   "authorships"             [{"author"       {"id" "https://openalex.org/A1"
                                               "display_name" "Ashish Vaswani"
                                               "orcid" "https://orcid.org/0000-0001-0000-0001"}
                               "institutions" [{"id" "https://openalex.org/I1"
                                                "display_name" "Google" "ror" "https://ror.org/00njsd438"
                                                "country_code" "US" "type" "company"}]}
                              {"author"       {"id" "https://openalex.org/A2"
                                               "display_name" "Noam Shazeer" "orcid" nil}
                               "institutions" []}]})

(deftest doi-for-test
  (is (= "10.1145/3292500" (openalex/doi-for {:kind :doi :id "10.1145/3292500"})))
  (is (= "10.48550/arxiv.2401.12345" (openalex/doi-for {:kind :arxiv :id "2401.12345"}))))

(deftest work-url-test
  (testing "the DOI keeps its slashes (OpenAlex matches the literal path)"
    (is (= "https://api.openalex.org/works/doi:10.1145/3292500.3330701"
           (openalex/work-url {:kind :doi :id "10.1145/3292500.3330701"}))))
  (testing "a stray fragment/space in the DOI is percent-encoded, host untouched"
    (let [url (openalex/work-url {:kind :doi :id "10.1/x#y z"})]
      (is (str/starts-with? url "https://api.openalex.org/works/doi:10.1/x"))
      (is (not (str/includes? url "#")) "the # can't open a fragment")
      (is (not (str/includes? url " ")) "the space is encoded"))))

(deftest normalize-test
  (let [g (openalex/normalize work)]
    (testing "metadata"
      (is (= "Attention Is All You Need" (:title g)))
      (is (= "The dominant models" (:abstract g)) "abstract reconstructed from the inverted index")
      (is (= (Instant/parse "2017-06-12T00:00:00Z") (:published g)))
      (is (= {:name "arXiv" :openalex-id "https://openalex.org/S456" :type "repository"} (:venue g))))
    (testing "author graph carries ORCID + institutions (ROR/country/OpenAlex id)"
      (is (= 2 (count (:authors g))))
      (let [v (first (:authors g))]
        (is (= "Ashish Vaswani" (:name v)))
        (is (= "https://orcid.org/0000-0001-0000-0001" (:orcid v)))
        (is (= "https://openalex.org/A1" (:openalex-id v)))
        (is (= [{:name "Google" :openalex-id "https://openalex.org/I1"
                 :ror "https://ror.org/00njsd438" :country "US"}]
               (:institutions v))))
      (is (nil? (:orcid (second (:authors g)))) "missing ORCID stays nil")
      (is (= [] (:institutions (second (:authors g))))))))

(deftest parse-graph-test
  (testing "a full work body → normalized graph"
    (is (= "Attention Is All You Need"
           (:title (openalex/parse-graph (json/write-json-str work))))))
  (testing "nil / unparseable / non-work body → nil"
    (is (nil? (openalex/parse-graph nil)))
    (is (nil? (openalex/parse-graph "not json")))
    (is (nil? (openalex/parse-graph (json/write-json-str {"meta" {"count" 0}})))
        "an OpenAlex shape with no work id is a miss")))
