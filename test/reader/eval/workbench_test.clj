(ns reader.eval.workbench-test
  "The Workbench feedback loop through the real ring handler: the queue serves a
   case with flaggable items, posting a verdict writes the golden label and
   redirects to the next, the queue drains, and the Overview reflects the score."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.client :as http]
            [org.httpkit.server :as hk-server]
            [reader.db.crud :as crud]
            [reader.domain.tags :as tags]
            [reader.eval.labels :as labels]
            [reader.eval.queue :as queue]
            [reader.eval.test-support :refer [with-eval-system]]
            [reader.ingest.tag-events :as tag-events]
            [reader.test-support.auth :as auth]
            [ring.mock.request :as mock]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-9))
(defn- op [req] (auth/authed req (auth/token "op@x.test")))
(defn- GET  [h uri] (h (op (mock/request :get uri))))
(defn- POST [h uri params] (h (op (mock/request :post uri params))))

(deftest tagging-workbench-loop-test
  (with-eval-system [system]
    (let [ds  (:reader.db/datasource system)
          h   (:reader.concerns.reitit/ring-handler system)
          aid (:articles/id (crud/create! ds :articles {:title "Attention" :slug "attn"
                                                        :canonical-url "https://x/attn"}))
          ml  (:tags/id (crud/create! ds :tags {:slug "machine-learning" :label "machine learning"}))
          nlp (:tags/id (crud/create! ds :tags {:slug "nlp" :label "nlp"}))]
      (tags/set-baseline! ds "article" aid [{:tag-id ml} {:tag-id nlp}])
      (tag-events/record! ds {:readable-type "article" :readable-id aid :outcome :done
                              :model "m" :tag-count 2 :duration-ms 100 :provenance {}})

      (testing "serves the case with flaggable tag chips + the Datastar layer"
        (let [resp (GET h "/workbench?p=tagging")]
          (is (= 200 (:status resp)))
          (is (str/includes? (:body resp) "Attention"))
          (is (str/includes? (:body resp) "machine learning"))
          (is (str/includes? (:body resp) "name=\"wrong\""))
          (is (str/includes? (:body resp) "/static/vendor/datastar.js"))
          ;; the submit is intercepted by Datastar (quotes are HTML-escaped, decoded by the browser)
          (is (str/includes? (:body resp) "data-on-submit__prevent"))))

      (testing "a verdict (nlp flagged wrong) writes the golden label and redirects to the next"
        (let [resp (POST h "/workbench/tagging"
                     {"readable-type" "article" "readable-id" (str aid) "wrong" "nlp"})]
          (is (= 303 (:status resp)))
          (is (= "/workbench?p=tagging" (get-in resp [:headers "location"]))))
        (let [{:keys [labeled precision recall]} (labels/tagging-score ds)]
          (is (= 1 labeled))
          ;; golden {machine-learning} vs production {ml, nlp}: tp1 fp1 fn0
          (is (close? 0.5 precision))
          (is (close? 1.0 recall))))

      (testing "the queue drains and the workbench shows the caught-up state"
        (is (nil? (queue/tagging-next ds)))
        (is (str/includes? (:body (GET h "/workbench?p=tagging")) "All caught up")))

      (testing "the overview reports the live score"
        (let [resp (GET h "/overview")]
          (is (= 200 (:status resp)))
          (is (str/includes? (:body resp) "Accuracy vs. labels")))))))

(deftest extraction-workbench-loop-test
  (with-eval-system [system]
    (let [ds  (:reader.db/datasource system)
          h   (:reader.concerns.reitit/ring-handler system)
          url "https://x/scoop"
          aff (:affiliations/id (crud/create! ds :affiliations {:name "Example News" :slug "example-news" :type "newspaper"}))
          a1  (:authors/id (crud/create! ds :authors {:name "Jane Roe" :slug "jane-roe"}))
          a2  (:authors/id (crud/create! ds :authors {:name "John Doe" :slug "john-doe"}))
          aid (:articles/id (crud/create! ds :articles {:title "Scoop" :slug "scoop"
                                                        :canonical-url url :affiliation-id aff}))]
      (crud/create! ds :authorships {:author-id a1 :readable-type "article" :readable-id aid :ordinal 0})
      (crud/create! ds :authorships {:author-id a2 :readable-type "article" :readable-id aid :ordinal 1})
      (crud/create! ds :extraction-events {:url url :domain "x" :outcome "done" :author-count 2})

      (testing "serves the case with author chips + a source flag"
        (let [resp (GET h "/workbench?p=extraction")]
          (is (= 200 (:status resp)))
          (is (str/includes? (:body resp) "Jane Roe"))
          (is (str/includes? (:body resp) "name=\"author-wrong\""))
          (is (str/includes? (:body resp) "name=\"source-wrong\""))))

      (testing "a verdict (john-doe wrong, source correct) writes the golden label"
        (let [resp (POST h "/workbench/extraction"
                     {"subject-url" url "author-wrong" "john-doe"})]
          (is (= 303 (:status resp))))
        (let [{:keys [byline source]} (labels/extraction-score ds)]
          ;; golden byline {jane-roe} vs production {jane-roe, john-doe}: p .5 r 1
          (is (close? 0.5 (:precision byline)))
          (is (close? 1.0 (:recall byline)))
          ;; source not flagged → golden = production → accurate
          (is (close? 1.0 (:accuracy source)))))

      (testing "the extraction overview renders live coverage + accuracy"
        (let [resp (GET h "/overview?p=extraction")]
          (is (= 200 (:status resp)))
          (is (str/includes? (:body resp) "Field coverage"))
          (is (str/includes? (:body resp) "Source acc.")))))))

(defn- tagged-article! [ds title slug url]
  (let [aid (:articles/id (crud/create! ds :articles {:title title :slug slug :canonical-url url}))
        t   (:tags/id (crud/create! ds :tags {:slug (str slug "-t") :label slug}))]
    (tags/set-baseline! ds "article" aid [{:tag-id t}])
    (tag-events/record! ds {:readable-type "article" :readable-id aid :outcome :done
                            :model "m" :tag-count 1 :duration-ms 1 :provenance {}})
    aid))

(deftest tagging-verdict-sse-test
  ;; Real HTTP against the running test server — the only way to exercise the
  ;; http-kit SSE stream (ring-mock can't). Proves the Datastar verdict path:
  ;; write the label, then stream the next case as a datastar-patch-elements event.
  (with-eval-system [system]
    (let [ds   (:reader.db/datasource system)
          port (hk-server/server-port (:reader.concerns/http-kit system))
          aid  (tagged-article! ds "First case" "first" "https://x/first")
          _    (tagged-article! ds "Second case" "second" "https://x/second")
          resp @(http/request
                 {:method  :post
                  :url     (str "http://localhost:" port "/workbench/tagging")
                  :headers {"datastar-request" "true"
                            "content-type"     "application/x-www-form-urlencoded"
                            "cookie"            (str "hanko=" (auth/token "op@x.test"))}
                  :body    (str "readable-type=article&readable-id=" aid "&wrong=first-t")})]
      (testing "responds with a Datastar SSE patch of the next case (no full reload)"
        (is (= 200 (:status resp)))
        (is (str/includes? (str (get-in resp [:headers :content-type])) "text/event-stream"))
        (is (str/includes? (:body resp) "datastar-patch-elements"))
        (is (str/includes? (:body resp) "id=\"wb\""))
        (is (str/includes? (:body resp) "Second case") "the next case streams in"))
      (testing "the verdict was written"
        (is (= 1 (:labeled (queue/tagging-progress ds))))))))
