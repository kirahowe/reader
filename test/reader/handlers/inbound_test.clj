(ns reader.handlers.inbound-test
  "The inbound-email receiver seam. The :webhook impl (prod) is built in
   isolation and driven with HMAC-signed requests — the contract the Cloudflare
   worker implements. The :direct impl (dev/test/PR) is exercised through the
   full test router, which wires :direct — proving the route is public and
   reaches it, and that a raw .eml runs the same downstream as the webhook."
  (:require [charred.api :as json]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [reader.db.crud :as crud]
            [reader.domain.inboxes :as inboxes]
            [reader.storage :as storage]
            [reader.test-support.setup :refer [with-system]]
            [reader.web.signature :as sig]
            [ring.mock.request :as mock])
  (:import (java.net URLEncoder)))

(def ^:private secret "test-inbound-secret")

(defn- now-secs [] (quot (System/currentTimeMillis) 1000))

(defn- ingest-jobs [ds] (crud/find-many ds :jobs {:queue-name "ingest-email"}))

;; ── :webhook (prod) — built in isolation, driven with signed requests ─────

(defn- webhook-handler [ds]
  (:reader.handlers/inbound
   (ig/init {:reader.handlers/inbound {:impl :webhook :datasource ds :hmac-secret secret}})))

(defn- post
  ([handler ts raw] (post handler ts raw (sig/sign secret (str ts "." raw))))
  ([handler ts raw signature]
   (-> (mock/request :post "/api/inbound")
       (mock/content-type "application/json")
       (mock/body raw)
       (mock/header "x-reader-timestamp" (str ts))
       (mock/header "x-reader-signature" signature)
       handler)))

(defn- json-post [handler ts payload] (post handler ts (json/write-json-str payload)))

(deftest inbound-webhook
  (with-system [system]
    (let [ds      (:reader.db/datasource system)
          handler (webhook-handler ds)
          uid     (:users/id (crud/create! ds :users {:email "sub@x.test"}))
          alias   (:email-inboxes/alias (inboxes/find-or-provision! ds uid "inbox.reader.test"))
          payload {:alias alias :r2-key "inbox/abc.eml" :message-id "<m1@stratechery.com>"
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

      (testing "a tampered body fails the signature check"
        (let [raw (json/write-json-str payload) ts (now-secs)]
          (is (= 401 (:status (post handler ts (str raw " ") (sig/sign secret (str ts "." raw))))))))

      (testing "a stale timestamp is rejected even with a valid signature"
        (is (= 401 (:status (json-post handler (- (now-secs) 1000) payload)))))

      (testing "a well-signed notification for an unknown alias is a 404"
        (is (= 404 (:status (json-post handler (now-secs) (assoc payload :alias "nobody@inbox.reader.test"))))))

      (testing "a malformed or schema-invalid payload is a 400"
        (is (= 400 (:status (post handler (now-secs) "{not json"))))
        (is (= 400 (:status (json-post handler (now-secs) (dissoc payload :size))))))

      (testing "only the one valid notification enqueued a job"
        (is (= 1 (count (ingest-jobs ds))) "no rejected request enqueued anything")))))

;; ── :direct (dev/test/PR) — through the router, a raw .eml ─────────────────

(def ^:private eml
  "From: A Sender <a@b.test>\r\nSubject: Hi\r\nMessage-ID: <d1@b.test>\r\n\r\nbody text")

(deftest inbound-direct
  (with-system [system]
    (let [ds     (:reader.db/datasource system)
          router (:reader.concerns.reitit/ring-handler system)
          store  (:reader.storage/store system)
          uid    (:users/id (crud/create! ds :users {:email "direct@x.test"}))
          alias  (:email-inboxes/alias (inboxes/find-or-provision! ds uid "inbox.reader.test"))
          deliver (fn [a body]
                    (-> (mock/request :post (str "/api/inbound?alias=" (URLEncoder/encode a "UTF-8")))
                        (mock/content-type "message/rfc822")
                        (mock/body body)
                        router))]

      (testing "a raw .eml for a known alias is accepted, stored, and enqueued (route is public)"
        (is (= 202 (:status (deliver alias eml))) "202 from the direct handler, not a 303 to /login")
        (let [jobs (ingest-jobs ds)]
          (is (= 1 (count jobs)))
          (is (= (str uid) (get-in (first jobs) [:jobs/payload :user-id])))
          (let [k (get-in (first jobs) [:jobs/payload :r2-key])]
            (is (= eml (String. ^bytes (storage/get-object store k) "UTF-8")) "the raw .eml was stored"))))

      (testing "a missing alias is a 400"
        (is (= 400 (:status (-> (mock/request :post "/api/inbound")
                                (mock/content-type "message/rfc822")
                                (mock/body eml)
                                router)))))

      (testing "an unknown alias is a 404"
        (is (= 404 (:status (deliver "nobody@inbox.reader.test" eml)))))

      (testing "only the one valid delivery enqueued a job"
        (is (= 1 (count (ingest-jobs ds))))))))
