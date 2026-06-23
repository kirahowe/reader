(ns reader.handlers.readables-test
  "Handler tests for the URL-paste ingest flow. The handlers are pulled from the
   booted test system (so their namespace is loaded by integrant, not a
   side-effecting require) and called with bare ring maps."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reader.db.crud :as crud]
            [reader.jobs :as jobs]
            [reader.papers :as papers]
            [reader.test-support.setup :refer [with-system]]))

(deftest create-and-poll-test
  (with-system [system]
    (let [ds     (:reader.db/datasource system)
          create (:reader.handlers.readables/create system)
          row    (:reader.handlers.readables/row system)
          uid    (:users/id (crud/create! ds :users {:email "h@x.test"}))
          url    "https://example.com/handler-article"]

      (testing "POST /readables over HTMX creates the placeholder and returns the importing row"
        (let [resp (create {:user-id uid :params {"url" url} :headers {"hx-request" "true"}})]
          (is (= 200 (:status resp)))
          (is (str/includes? (:body resp) "Importing"))
          (is (str/includes? (:body resp) url))
          (is (= 1 (count (crud/find-many ds :jobs {:queue-name "extract-article"}))))))

      (testing "an invalid url is rejected without creating a job"
        (let [resp (create {:user-id uid :params {"url" "not-a-url"} :headers {"hx-request" "true"}})]
          (is (= 303 (:status resp)))
          (is (= 1 (count (crud/find-many ds :jobs {:queue-name "extract-article"}))) "no new job")))

      (let [qi  (crud/find-1 ds :queue-items {:user-id uid})
            qid (:queue-items/id qi)
            aid (:queue-items/readable-id qi)]
        (testing "GET /queue/:id/row reports importing while the job is pending"
          (is (str/includes? (:body (row {:user-id uid :path-params {:id (str qid)}})) "Importing")))

        (testing "and swaps in the real, link-bearing row once the article is extracted"
          (crud/update! ds :articles aid {:title "Extracted Title" :body-html "<p>body</p>"})
          (let [resp (row {:user-id uid :path-params {:id (str qid)}})]
            (is (str/includes? (:body resp) "Extracted Title"))
            (is (str/includes? (:body resp) (str "/queue/" qid)))
            (is (not (str/includes? (:body resp) "Importing")))))

        (testing "polling a row you can't see returns a terminal, empty fragment (no leak, stops polling)"
          (let [other (:users/id (crud/create! ds :users {:email "other@x.test"}))
                resp  (row {:user-id other :path-params {:id (str qid)}})]
            (is (= 200 (:status resp)))
            (is (str/blank? (:body resp)) "empty body removes the row instead of swapping a full 404 page")
            (is (not (str/includes? (:body resp) "Importing")))))))))

(deftest paper-paste-routes-to-the-paper-path-test
  (with-system [system]
    (let [ds     (:reader.db/datasource system)
          create (:reader.handlers.readables/create system)
          uid    (:users/id (crud/create! ds :users {:email "pp@x.test"}))]
      (testing "pasting an arXiv link starts a paper (its own ingest path), not an article fetch"
        (let [resp (create {:user-id uid
                            :params  {"url" "https://arxiv.org/abs/2401.12345"}
                            :headers {"hx-request" "true"}})]
          (is (= 200 (:status resp)))
          (is (str/includes? (:body resp) "Importing"))
          (is (str/includes? (:body resp) "arXiv:2401.12345") "the importing row is labeled with the paper")
          (is (= 1 (count (crud/find-many ds :jobs {:queue-name "extract-paper"}))))
          (is (zero? (count (crud/find-many ds :jobs {:queue-name "extract-article"})))
              "an arXiv link is not routed to article ingest")
          (is (= 1 (count (crud/find-many ds :papers {:arxiv-id "2401.12345"})))))))))

(deftest not-indexed-paper-row-is-honest-and-terminal-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          row (:reader.handlers.readables/row system)
          uid (:users/id (crud/create! ds :users {:email "ni@x.test"}))]
      (testing "a DOI OpenAlex hasn't indexed renders a non-polling 'check back later' row"
        (let [{:keys [queue-item]} (papers/start! ds uid {:kind :doi :id "10.9/not-indexed"})
              qid (:queue-items/id queue-item)
              job (jobs/claim-next! ds "extract-paper")]
          (jobs/fail! ds job "not indexed" {:fatal? true :error-class :paper-not-indexed})
          (let [resp (row {:user-id uid :path-params {:id (str qid)}})]
            (is (= 200 (:status resp)))
            (is (str/includes? (:body resp) "Not indexed yet") "honest copy, not a generic failure")
            (is (not (str/includes? (:body resp) "add manually")) "no misleading article-only fallback")
            (is (not (str/includes? (:body resp) "Importing")) "terminal — polling stops")))))))

(deftest failed-row-is-terminal-test
  (with-system [system]
    (let [ds     (:reader.db/datasource system)
          create (:reader.handlers.readables/create system)
          row    (:reader.handlers.readables/row system)
          uid    (:users/id (crud/create! ds :users {:email "fr@x.test"}))
          fail!  (fn [queue-name] (jobs/fail! ds (jobs/claim-next! ds queue-name) "boom" {:fatal? true}))
          row-of (fn [qid] (:body (row {:user-id uid :path-params {:id (str qid)}})))]

      (testing "a permanently failed article renders a terminal row — archive only, no manual-add"
        (create {:user-id uid :params {"url" "https://example.com/dead-article"} :headers {"hx-request" "true"}})
        (fail! "extract-article")
        (let [qi   (crud/find-1 ds :queue-items {:user-id uid :readable-type "article"})
              body (row-of (:queue-items/id qi))]
          (is (str/includes? body "Couldn’t import"))
          (is (str/includes? body "/archive") "the only action is to archive it")
          (is (not (str/includes? body "add manually")) "the manual-add form is gone")))

      (testing "a permanently failed paper is likewise terminal"
        (let [{:keys [queue-item]} (papers/start! ds uid {:kind :doi :id "10.7/dead"})]
          (fail! "extract-paper")
          (let [body (row-of (:queue-items/id queue-item))]
            (is (str/includes? body "Couldn’t import"))
            (is (not (str/includes? body "add manually")))))))))
