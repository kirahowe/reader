(ns reader.ingest-email-test
  "End-to-end :ingest-email: a raw .eml in blob storage -> a queued newsletter
   issue, its source, author, and authorship. Real embedded Postgres + the
   in-memory storage stub via with-system."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reader.db.crud :as crud]
            [reader.ingest :as ingest]
            [reader.ingest.email :as email]
            [reader.ingest.email-test :refer [gmail-forward-eml raw-eml]]
            [reader.domain.reading :as reading]
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
          (is (= "https://stratechery.com/unsub?u=42" (:newsletter-issues/unsubscribe-url issue))
              "the List-Unsubscribe target is captured for in-app unsubscribe")
          (is (= email/extraction-version (:newsletter-issues/extraction-version issue)))
          (is (seq (:newsletter-issues/extraction-provenance issue)))
          (is (false? (:newsletter-issues/is-forwarded issue)))
          (is (= "Ben Thompson" (:newsletter-issues/original-from-name issue)))
          (is (= "ben@stratechery.com" (:newsletter-issues/original-from-email issue)))
          (is (= mid (:newsletter-issues/original-message-id issue)))

          (testing "the source is a newsletter affiliation named from the sender domain"
            (let [aff (crud/by-id ds :affiliations (:newsletter-issues/affiliation-id issue))]
              (is (= "newsletter" (:affiliations/type aff)))
              (is (= "Stratechery" (:affiliations/name aff))))
            (is (some? (crud/find-1 ds :newsletter-sources {:inbound-email-alias "@stratechery.com"})))
            (is (some? (crud/find-1 ds :newsletter-source-aliases {:alias "@stratechery.com"}))))

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

(deftest forwarded-email-persists-the-newsletter-not-the-forwarder
  (with-system [system]
    (let [ds    (:reader.db/datasource system)
          store (:reader.storage/store system)
          uid   (:users/id (crud/create! ds :users {:email "forward-reader@x.test"}))
          key   "inbox/forwarded-dispatch.eml"
          mid   "<delivery-99@personal.test>"]
      (storage/put-object store key (.getBytes ^String gmail-forward-eml "UTF-8") "message/rfc822")
      (ingest/ingest-email! ds store {:user-id (str uid) :r2-key key :message-id mid})
      (let [issue   (crud/find-1 ds :newsletter-issues {:message-id mid})
            aff     (crud/by-id ds :affiliations (:newsletter-issues/affiliation-id issue))
            authors (->> (crud/find-many ds :authorships
                                         {:readable-type "newsletter_issue"
                                          :readable-id (:newsletter-issues/id issue)})
                         (sort-by :authorships/ordinal)
                         (mapv #(->> (:authorships/author-id %) (crud/by-id ds :authors)
                                     :authors/name)))]
        (testing "newsletter identity and body replace the outer forwarding envelope"
          (is (= "The Systems Dispatch" (:newsletter-issues/subject issue)))
          (is (= "Dispatch Delivery" (:newsletter-issues/original-from-name issue)))
          (is (= "newsletter@dispatch.example" (:newsletter-issues/original-from-email issue)))
          (is (true? (:newsletter-issues/is-forwarded issue)))
          (is (= email/extraction-version (:newsletter-issues/extraction-version issue)))
          (is (= "https://dispatch.example/issues/systems" (:newsletter-issues/original-url issue)))
          (is (str/includes? (:newsletter-issues/body-html issue) "actual newsletter starts here"))
          (is (not (str/includes? (:newsletter-issues/body-html issue) "You will love this"))))
        (testing "publication and byline metadata come from the newsletter"
          (is (= "Systems Dispatch" (:affiliations/name aff)))
          (is (= ["Priya Shah" "Mateo Ruiz"] authors))
          (is (not-any? #{"Alice Forwarder"} authors))
          (is (= (:affiliations/id aff)
                 (:newsletter-source-aliases/affiliation-id
                  (crud/find-1 ds :newsletter-source-aliases {:alias "@dispatch.example"})))))))))

(deftest reprocess-newsletter-repairs-legacy-data-in-place
  (with-system [system]
    (let [ds       (:reader.db/datasource system)
          store    (:reader.storage/store system)
          user     (crud/create! ds :users {:email "repair-reader@x.test"})
          old-aff  (crud/create! ds :affiliations
                                 {:name "Personal Mail" :slug "personal-mail" :type "newsletter"})
          key      "inbox/legacy-forward.eml"
          issue    (crud/create! ds :newsletter-issues
                                 {:affiliation-id       (:affiliations/id old-aff)
                                  :subject              "Fwd: The Systems Dispatch"
                                  :body-html            "<p>Outer forwarding chrome.</p>"
                                  :raw-email-object-key key
                                  :message-id           "<delivery-99@personal.test>"})
          alice    (crud/create! ds :authors
                                 {:name "Alice Forwarder" :slug "alice-forwarder"})
          queue    (reading/enqueue! ds (:users/id user) "newsletter_issue"
                                     (:newsletter-issues/id issue) {:source "email"})]
      (crud/create! ds :authorships {:author-id (:authors/id alice)
                                     :readable-type "newsletter_issue"
                                     :readable-id (:newsletter-issues/id issue)})
      (reading/mark-read! ds (:users/id user) (:queue-items/id queue))
      (storage/put-object store key (.getBytes ^String gmail-forward-eml "UTF-8") "message/rfc822")

      (let [{:keys [applied?]} (ingest/reprocess-newsletter!
                                ds store {:newsletter-issue-id (str (:newsletter-issues/id issue))})
            repaired (crud/by-id ds :newsletter-issues (:newsletter-issues/id issue))
            names    (->> (crud/find-many ds :authorships
                                          {:readable-type "newsletter_issue"
                                           :readable-id (:newsletter-issues/id issue)})
                          (sort-by :authorships/ordinal)
                          (mapv #(->> (:authorships/author-id %) (crud/by-id ds :authors)
                                      :authors/name)))]
        (is (true? applied?))
        (is (= (:newsletter-issues/id issue) (:newsletter-issues/id repaired)))
        (is (= email/extraction-version (:newsletter-issues/extraction-version repaired)))
        (is (= "The Systems Dispatch" (:newsletter-issues/subject repaired)))
        (is (= ["Priya Shah" "Mateo Ruiz"] names))
        (is (= "read" (:queue-items/state (crud/by-id ds :queue-items (:queue-items/id queue))))
            "repair does not resurrect a read queue item")
        (is (= 1 (count (crud/find-many ds :jobs {:queue-name "tag-readable"}))))
        (is (false? (:applied? (ingest/reprocess-newsletter!
                                ds store {:newsletter-issue-id (str (:newsletter-issues/id issue))})))
            "the same extraction version is a no-op")
        (is (= 1 (count (crud/find-many ds :jobs {:queue-name "tag-readable"})))
            "a no-op repair does not enqueue redundant tagging")))))

(deftest stale-reprocessing-scheduler-is-bounded-and-idempotent
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          aff (crud/create! ds :affiliations {:name "Legacy" :slug "legacy" :type "newsletter"})
          mk  (fn [n]
                (crud/create! ds :newsletter-issues
                              {:affiliation-id (:affiliations/id aff)
                               :subject (str "Legacy " n) :body-html "old"
                               :raw-email-object-key (str "inbox/legacy-" n ".eml")}))
          a   (mk 1)
          b   (mk 2)]
      (is (= #{(:newsletter-issues/id a)}
             (set (ingest/enqueue-newsletter-reprocessing! ds {:limit 1}))))
      (is (= #{(:newsletter-issues/id b)}
             (set (ingest/enqueue-newsletter-reprocessing! ds {:limit 100}))))
      (is (empty? (ingest/enqueue-newsletter-reprocessing! ds)))
      (is (= 2 (count (crud/find-many ds :jobs {:queue-name "reprocess-newsletter"})))))))
