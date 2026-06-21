(ns reader.ingest.tag-job-test
  "Integration tests for the tag-readable job: infer (stubbed) -> embed (stubbed)
   -> dedup -> persist baseline + embedding + eval event, against embedded
   Postgres. The model edges are stubbed; everything else is real."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [reader.db.crud :as crud]
            [reader.ingest.tag-job :as job]
            [reader.test-support.setup :refer [with-system]]))

(defn- seed-article [ds]
  (let [aff (crud/create! ds :affiliations {:name "Aff" :slug "aff" :type "blog"})]
    (crud/create! ds :articles {:affiliation-id (:affiliations/id aff)
                                :title "On B-Trees" :slug "on-b-trees"
                                :canonical-url "https://example.test/b-trees"
                                :body-html "<p>Indexes and storage.</p>"})))

(defn- fixed-tagger [proposals]
  (fn [_content _existing] {:tags proposals :model "stub-model"}))

;; Orthonormal vectors per input, so distinct labels never collapse in dedup.
(defn- orthonormal-embed [inputs]
  (let [n (count inputs)]
    (mapv (fn [i] (assoc (vec (repeat n 0.0)) i 1.0)) (range n))))

(def ^:private deps
  {:infer-tags  (fixed-tagger [{:label "Databases" :confidence 0.9}
                               {:label "Indexing" :confidence 0.8}])
   :embed       orthonormal-embed
   :threshold   0.9
   :embed-model "stub-embed"})

(deftest tag-readable!-persists-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          aid (:articles/id (seed-article ds))]
      (job/tag-readable! ds {:readable-type "article" :readable-id (str aid)} deps)
      (testing "writes one baseline row per proposed tag, deduping nothing"
        (is (= 2 (count (crud/find-many ds :readable-tags {:readable-id aid}))))
        (is (= 2 (count (crud/find-many ds :tags)))))
      (testing "stores the readable's own embedding"
        (let [emb (crud/find-1 ds :readable-embeddings {:readable-id aid})]
          (is (some? emb))
          (is (= "stub-embed" (:readable-embeddings/model emb)))))
      (testing "records a done eval event with the model and tag count"
        (let [ev (crud/find-1 ds :tagging-events {:readable-id aid})]
          (is (= "done" (:tagging-events/outcome ev)))
          (is (= "stub-model" (:tagging-events/model ev)))
          (is (= 2 (:tagging-events/tag-count ev))))))))

(deftest tag-readable!-idempotent-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          aid (:articles/id (seed-article ds))]
      (job/tag-readable! ds {:readable-type "article" :readable-id (str aid)} deps)
      (job/tag-readable! ds {:readable-type "article" :readable-id (str aid)} deps)
      (testing "re-running replaces rather than accumulating"
        (is (= 2 (count (crud/find-many ds :readable-tags {:readable-id aid})))
            "baseline still has 2 rows")
        (is (= 2 (count (crud/find-many ds :tags)))
            "no duplicate vocabulary entries")
        (is (= 1 (count (crud/find-many ds :readable-embeddings {:readable-id aid})))
            "one embedding row")))))

(deftest tag-readable!-model-failure-test
  (with-system [system]
    (let [ds      (:reader.db/datasource system)
          aid     (:articles/id (seed-article ds))
          failing (assoc deps :embed (fn [_] (throw (ex-info "embed unavailable"
                                                             {:error-class :model-transport}))))]
      (testing "a transient model failure is recorded and rethrown (the worker retries)"
        (is (thrown? clojure.lang.ExceptionInfo
                     (job/tag-readable! ds {:readable-type "article" :readable-id (str aid)} failing)))
        (is (= "failed" (:tagging-events/outcome
                         (first (crud/find-many ds :tagging-events {:readable-id aid}))))))
      (testing "no partial tags or embedding are written on failure"
        (is (empty? (crud/find-many ds :readable-tags {:readable-id aid})))
        (is (empty? (crud/find-many ds :readable-embeddings {:readable-id aid})))))))

(deftest handler-skips-when-no-model-configured-test
  (with-system [system]
    (let [ds      (:reader.db/datasource system)
          aid     (:articles/id (seed-article ds))
          ;; require-model? with neither tagger nor embed wired = the prod
          ;; pre-secrets state: skip rather than write stub tags to the baseline.
          handler (ig/init-key :reader.ingest.tag-job/handler
                               {:require-model? true :skip-retry-secs 900})]
      (handler ds {:readable-type "article" :readable-id (str aid)})
      (testing "records a :skipped event and writes no baseline or embedding"
        (is (= "skipped" (:tagging-events/outcome (crud/find-1 ds :tagging-events {:readable-id aid}))))
        (is (empty? (crud/find-many ds :readable-tags {:readable-id aid})))
        (is (empty? (crud/find-many ds :readable-embeddings {:readable-id aid}))))
      (testing "re-enqueues a pending tag-readable job for a later attempt"
        (let [jobs (crud/find-many ds :jobs {:queue-name "tag-readable"})]
          (is (= 1 (count jobs)))
          (is (= "pending" (:jobs/state (first jobs))))
          (is (= (str aid) (:readable-id (:jobs/payload (first jobs))))))))))

(deftest tag-readable!-missing-readable-test
  (with-system [system]
    (let [ds (:reader.db/datasource system)]
      (testing "a missing readable is fatal (no pointless retries) and recorded as failed"
        (let [ex (try (job/tag-readable! ds {:readable-type "article"
                                             :readable-id (str (random-uuid))} deps)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex))
          (is (= :missing-readable (:error-class (ex-data ex))))
          (is (true? (:fatal? (ex-data ex)))))
        (is (= "failed" (:tagging-events/outcome
                         (first (crud/find-many ds :tagging-events)))))))))
