(ns reader.papers-test
  (:require [charred.api :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reader.db.crud :as crud]
            [reader.jobs :as jobs]
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

(def ^:private arxiv-atom
  "<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry>
     <title>Attention Is All You Need</title>
     <summary>We propose the Transformer.</summary>
     <published>2017-06-12T17:57:34Z</published>
     <author><name>Ashish Vaswani</name></author>
     <author><name>Noam Shazeer</name></author>
   </entry></feed>")

(defn- mk-user [ds email] (:users/id (crud/create! ds :users {:email email})))

(defn- request-fn
  "Stub for reader.http/request!: routes a url to a canned {:status :body} and
   delivers it as a promise. nil for any of `:openalex`/`:arxiv-html`/`:arxiv-atom`
   ⇒ a 404 at that source."
  [{:keys [openalex arxiv-html arxiv-atom]}]
  (fn [url]
    (let [body (cond
                 (str/includes? url "api.openalex.org")          openalex
                 (str/starts-with? url "https://arxiv.org/html") arxiv-html
                 (str/includes? url "export.arxiv.org")          arxiv-atom)]
      (doto (promise)
        (deliver (if body {:status 200 :body body} {:status 404 :body nil}))))))

(deftest start-then-extract-builds-the-graph
  (with-system [system]
    (let [ds                         (:reader.db/datasource system)
          uid                        (mk-user ds "p@example.com")
          ref                        {:kind :arxiv :id "1706.03762"}
          {:keys [paper queue-item]} (papers/start! ds uid ref)
          pid                        (:papers/id paper)
          run!                       (fn [arxiv-html]
                                       (papers/extract-paper!
                                        ds {:paper-id pid :kind "arxiv" :id "1706.03762"}
                                        {:request-fn (request-fn {:openalex   (json/write-json-str work)
                                                                  :arxiv-html arxiv-html})}))]
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
        (run! "<p>body <math><mi>x</mi></math></p>")
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
        (run! nil)
        (let [vaswani (crud/find-1 ds :authors {:orcid orcid})]
          (is (= 2 (count (crud/find-many ds :authorships {:readable-id pid}))))
          (is (= 1 (count (crud/find-many ds :author-affiliations {:author-id (:authors/id vaswani)})))))))))

(deftest extract-falls-back-to-arxiv-api-when-openalex-misses
  (with-system [system]
    (let [ds              (:reader.db/datasource system)
          uid             (mk-user ds "fb@example.com")
          {:keys [paper]} (papers/start! ds uid {:kind :arxiv :id "1706.03762"})
          pid             (:papers/id paper)]
      (papers/extract-paper! ds {:paper-id pid :kind "arxiv" :id "1706.03762"}
                             {:request-fn (request-fn {:openalex   nil
                                                       :arxiv-atom arxiv-atom
                                                       :arxiv-html "<p>body</p>"})})
      (let [p      (crud/by-id ds :papers pid)
            aships (sort-by :authorships/ordinal (crud/find-many ds :authorships {:readable-id pid}))]
        (testing "the paper is filled from the arXiv API (title/abstract/body/venue)"
          (is (= "Attention Is All You Need" (:papers/title p)))
          (is (= "We propose the Transformer." (:papers/abstract p)))
          (is (str/includes? (:papers/body-html p) "body"))
          (is (= "preprint" (:affiliations/type (crud/by-id ds :affiliations (:papers/affiliation-id p))))))
        (testing "authors land as names only — no ORCID, no institution links"
          (is (= 2 (count aships)))
          (let [a0 (crud/by-id ds :authors (:authorships/author-id (first aships)))]
            (is (= "Ashish Vaswani" (:authors/name a0)))
            (is (nil? (:authors/orcid a0)))
            (is (empty? (crud/find-many ds :author-affiliations {:author-id (:authors/id a0)})))))))))

(deftest doi-not-in-openalex-is-fatal-and-recorded
  (with-system [system]
    (let [ds              (:reader.db/datasource system)
          uid             (mk-user ds "ni@example.com")
          {:keys [paper]} (papers/start! ds uid {:kind :doi :id "10.1/unknown"})
          pid             (:papers/id paper)
          thrown          (try
                            (papers/extract-paper! ds {:paper-id pid :kind "doi" :id "10.1/unknown"}
                                                   {:request-fn (request-fn {:openalex nil})}) ; OpenAlex 404
                            nil
                            (catch clojure.lang.ExceptionInfo e e))]
      (testing "a DOI OpenAlex answers 404 for is a fatal, terminal failure (don't thrash retries)"
        (is (some? thrown) "extract-paper! threw")
        (is (true? (:fatal? (ex-data thrown))))
        (is (= :paper-not-indexed (:error-class (ex-data thrown)))))
      (testing "and the failure is recorded like every other ingest failure"
        (let [ev (crud/find-1 ds :extraction-events {:url "https://doi.org/10.1/unknown"})]
          (is (= "failed" (:extraction-events/outcome ev)))
          (is (= "doi.org" (:extraction-events/domain ev)))
          (is (= "paper-not-indexed" (:extraction-events/error-class ev))))))))

(deftest status-distinguishes-not-indexed-from-a-hard-failure
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          uid (mk-user ds "st@example.com")]
      (testing "a job that failed because the paper isn't indexed yet → :not-indexed"
        (let [pid (:papers/id (:paper (papers/start! ds uid {:kind :doi :id "10.1/a"})))
              job (jobs/claim-next! ds "extract-paper")]
          (jobs/fail! ds job "not indexed" {:fatal? true :error-class :paper-not-indexed})
          (is (= :not-indexed (papers/status ds pid)))))
      (testing "a job that failed for any other reason → :failed"
        (let [pid (:papers/id (:paper (papers/start! ds uid {:kind :doi :id "10.1/b"})))
              job (jobs/claim-next! ds "extract-paper")]
          (jobs/fail! ds job "boom" {:fatal? true :error-class :unknown})
          (is (= :failed (papers/status ds pid))))))))
