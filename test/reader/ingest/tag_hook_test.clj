(ns reader.ingest.tag-hook-test
  "The ingest paths enqueue a tag-readable job when a readable is finalized.
   Driven through the newsletter path (the cheapest to exercise end-to-end);
   the article/paper finalize! hooks are the same one-liner."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [reader.db.crud :as crud]
            [reader.domain.newsletters :as newsletters]
            [reader.test-support.setup :refer [with-system]]))

(deftest record-issue!-enqueues-tag-job-test
  (with-system [system]
    (let [ds   (:reader.db/datasource system)
          user (crud/create! ds :users {:email "hooktest@example.com"})]
      (jdbc/with-transaction [tx ds]
        (newsletters/record-issue! tx (:users/id user)
                                   {:subject    "Weekly Roundup" :body-html "<p>hi</p>"
                                    :message-id "<m1@x>"         :raw-key   "inbox/x.eml"
                                    :from-email "ben@stratechery.com" :from-name "Ben"}))
      (testing "a newly recorded issue enqueues exactly one tag-readable job for it"
        (let [issue (crud/find-1 ds :newsletter-issues {:message-id "<m1@x>"})
              jobs  (crud/find-many ds :jobs {:queue-name "tag-readable"})
              pay   (:jobs/payload (first jobs))]
          (is (= 1 (count jobs)))
          (is (= "newsletter_issue" (:readable-type pay)))
          (is (= (str (:newsletter-issues/id issue)) (:readable-id pay)))
          (is (= 1 (:content-version pay)))))
      (testing "a redelivery of the same issue does not enqueue a second job"
        (jdbc/with-transaction [tx ds]
          (newsletters/record-issue! tx (:users/id user)
                                     {:subject    "Weekly Roundup" :body-html "<p>hi</p>"
                                      :message-id "<m1@x>"         :raw-key   "inbox/x.eml"
                                      :from-email "ben@stratechery.com" :from-name "Ben"}))
        (is (= 1 (count (crud/find-many ds :jobs {:queue-name "tag-readable"}))))))))
