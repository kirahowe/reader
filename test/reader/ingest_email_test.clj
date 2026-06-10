(ns reader.ingest-email-test
  "End-to-end :ingest-email: a raw .eml in blob storage -> a queued newsletter
   issue, its source, author, and authorship. Real embedded Postgres + the
   in-memory storage stub via with-system."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reader.db.crud :as crud]
            [reader.ingest :as ingest]
            [reader.ingest.email-test :refer [raw-eml]]
            [reader.reading :as reading]
            [reader.storage :as storage]
            [reader.test-support.setup :refer [with-system]]))

(def ^:private mid "<issue-42@stratechery.com>")

(deftest ingest-email-end-to-end
  (with-system [system]
    (let [ds    (:reader.db/datasource system)
          store (:reader.storage/store system)
          uid   (:users/id (crud/create! ds :users {:email "reader@x.test"}))
          k     "inbox/issue-42.eml"]
      (storage/put-object store k (.getBytes ^String raw-eml "UTF-8") "message/rfc822")

      (testing "ingest writes the issue, its newsletter source, author, authorship, and queues it"
        (ingest/ingest-email! ds store {:user-id (str uid) :r2-key k :message-id mid})
        (let [issue (crud/find-1 ds :newsletter-issues {:message-id mid})]
          (is (some? issue))
          (is (= "Weekly Update" (:newsletter-issues/subject issue)))
          (is (str/includes? (:newsletter-issues/body-html issue) "Hello"))
          (is (= k (:newsletter-issues/raw-email-object-key issue)))

          (testing "the source is a newsletter affiliation named from the sender domain"
            (let [aff (crud/by-id ds :affiliations (:newsletter-issues/affiliation-id issue))]
              (is (= "newsletter" (:affiliations/type aff)))
              (is (= "Stratechery" (:affiliations/name aff))))
            (is (some? (crud/find-1 ds :newsletter-sources {:inbound-email-alias "@stratechery.com"}))))

          (testing "the sender becomes the author"
            (let [link   (crud/find-1 ds :authorships {:readable-type "newsletter_issue"
                                                       :readable-id   (:newsletter-issues/id issue)})
                  author (crud/by-id ds :authors (:authorships/author-id link))]
              (is (= "Ben Thompson" (:authors/name author)))))

          (testing "it's queued unread for the addressed user"
            (let [qi (crud/find-1 ds :queue-items {:user-id uid :readable-id (:newsletter-issues/id issue)})]
              (is (= "newsletter_issue" (:queue-items/readable-type qi)))
              (is (= "unread" (:queue-items/state qi)))))))

      (testing "re-ingesting the same Message-ID is idempotent and leaves the queue item alone"
        (let [qi (crud/find-1 ds :queue-items {:user-id uid})]
          (reading/mark-read! ds uid (:queue-items/id qi))   ; the user reads it before the redelivery
          (ingest/ingest-email! ds store {:user-id (str uid) :r2-key k :message-id mid})
          (is (= 1 (count (crud/find-many ds :newsletter-issues {:message-id mid}))) "still one issue")
          (is (= 1 (count (crud/find-many ds :queue-items {:user-id uid}))) "still one queue item")
          (is (= "read" (:queue-items/state (crud/by-id ds :queue-items (:queue-items/id qi))))
              "the redelivery didn't resurrect the read item back to unread")))

      (testing "a missing stored object is a fatal error (no point retrying)"
        (is (thrown? clojure.lang.ExceptionInfo
                     (ingest/ingest-email! ds store {:user-id (str uid) :r2-key "absent" :message-id "x"})))))))
