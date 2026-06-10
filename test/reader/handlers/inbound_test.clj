(ns reader.handlers.inbound-test
  "The inbound-email webhook, driven through the full router (so the route's
   public? flag and the middleware stack are exercised). The test signs requests
   with reader.web.signature — the same contract the Cloudflare worker implements."
  (:require [charred.api :as json]
            [clojure.test :refer [deftest is testing]]
            [reader.db.crud :as crud]
            [reader.inboxes :as inboxes]
            [reader.test-support.setup :refer [with-system]]
            [reader.web.signature :as sig]
            [ring.mock.request :as mock]))

(def ^:private secret "test-inbound-secret")

(defn- now-secs [] (quot (System/currentTimeMillis) 1000))

(defn- post
  "A POST to /api/inbound with `raw` as the exact body, signed for `ts`."
  ([handler ts raw] (post handler ts raw (sig/sign secret (str ts "." raw))))
  ([handler ts raw signature]
   (-> (mock/request :post "/api/inbound")
       (mock/content-type "application/json")
       (mock/body raw)
       (mock/header "x-reader-timestamp" (str ts))
       (mock/header "x-reader-signature" signature)
       handler)))

(defn- json-post [handler ts payload]
  (post handler ts (json/write-json-str payload)))

(defn- ingest-jobs [ds] (crud/find-many ds :jobs {:queue-name "ingest-email"}))

(deftest inbound-webhook
  (with-system [system]
    (let [ds       (:reader.db/datasource system)
          handler  (:reader.concerns.reitit/ring-handler system)
          uid      (:users/id (crud/create! ds :users {:email "sub@x.test"}))
          alias    (:email-inboxes/alias (inboxes/find-or-provision! ds uid "inbox.reader.test"))
          payload  {:alias alias :r2-key "inbox/abc.eml" :message-id "<m1@stratechery.com>"
                    :from "Ben <ben@stratechery.com>" :subject "Weekly" :size 2048}]

      (testing "a valid signed notification for a known alias enqueues an :ingest-email job"
        (let [{:keys [status body]} (json-post handler (now-secs) payload)]
          (is (= 202 status))
          (is (= "accepted" body)))
        (let [jobs (ingest-jobs ds)]
          (is (= 1 (count jobs)))
          (is (= "inbox/abc.eml" (get-in (first jobs) [:jobs/payload :r2-key])))
          (is (= (str uid) (get-in (first jobs) [:jobs/payload :user-id])) "routed to the alias owner")
          (is (= "<m1@stratechery.com>" (get-in (first jobs) [:jobs/payload :message-id])))))

      (testing "an unsigned request is rejected (proving the route is public but signature-gated)"
        (let [{:keys [status]} (-> (mock/request :post "/api/inbound")
                                   (mock/content-type "application/json")
                                   (mock/body (json/write-json-str payload))
                                   handler)]
          (is (= 401 status) "401 from the handler, not a 303 to /login")))

      (testing "a tampered body fails the signature check"
        (let [raw (json/write-json-str payload)
              ts  (now-secs)]
          (is (= 401 (:status (post handler ts (str raw " ") (sig/sign secret (str ts "." raw))))))))

      (testing "a stale timestamp is rejected even with a valid signature"
        (is (= 401 (:status (json-post handler (- (now-secs) 1000) payload)))))

      (testing "a well-signed notification for an unknown alias is a 404"
        (is (= 404 (:status (json-post handler (now-secs) (assoc payload :alias "r-00000000000000000000000000000000@inbox.reader.test"))))))

      (testing "a malformed or schema-invalid payload is a 400"
        (is (= 400 (:status (post handler (now-secs) "{not json"))))
        (is (= 400 (:status (json-post handler (now-secs) (dissoc payload :size))))))

      (testing "only the one valid notification enqueued a job"
        (is (= 1 (count (ingest-jobs ds))) "no rejected request enqueued anything")))))
