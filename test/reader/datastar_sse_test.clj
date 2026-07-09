(ns reader.datastar-sse-test
  "The reader's Datastar paths over a real socket — the only way to exercise the
   http-kit SSE stream (ring-mock can't; a bare ring map has no channel). Each
   write endpoint, hit with the `datastar-request` header, must answer a
   text/event-stream that patches the page instead of redirecting: create
   prepends the importing row and clears the $url signal, archive removes the
   row, the read toggle re-renders the controls, and a tag add re-renders the
   tag editor."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.client :as http]
            [org.httpkit.server :as hk-server]
            [reader.db.crud :as crud]
            [reader.domain.reading :as reading]
            [reader.test-support.auth :as test-auth]
            [reader.test-support.setup :refer [with-system]]))

(defn- ds-headers []
  {"datastar-request" "true"
   "content-type"     "application/x-www-form-urlencoded"
   "cookie"           (str "hanko=" (test-auth/token))})

(defn- POST [port path & [body]]
  @(http/request {:method :post
                  :url    (str "http://localhost:" port path)
                  :headers (ds-headers)
                  :body   (or body "")}))

(defn- GET [port path]
  @(http/request {:method :get
                  :url    (str "http://localhost:" port path)
                  :headers (ds-headers)}))

(defn- sse? [resp]
  (str/includes? (str (get-in resp [:headers :content-type])) "text/event-stream"))

(deftest datastar-flows-test
  (with-system [system]
    (let [ds   (:reader.db/datasource system)
          port (hk-server/server-port (:reader.concerns/http-kit system))]

      (testing "POST /readables prepends the importing row into the list and clears $url"
        (let [resp (POST port "/readables" "url=https%3A%2F%2Fexample.com%2Fsse-article")]
          (is (= 200 (:status resp)))
          (is (sse? resp))
          (is (str/includes? (:body resp) "datastar-patch-elements"))
          (is (str/includes? (:body resp) "#readables-list"))
          (is (str/includes? (:body resp) "prepend"))
          (is (str/includes? (:body resp) "Importing"))
          (is (str/includes? (:body resp) "datastar-patch-signals") "the bound $url signal is cleared")))

      (let [uid (:users/id (crud/find-1 ds :users {:email test-auth/invited-email}))
            qi  (crud/find-1 ds :queue-items {:user-id uid})
            qid (:queue-items/id qi)]

        (testing "GET /queue/:id/row morphs the settled row in once extraction lands"
          (crud/update! ds :articles (:queue-items/readable-id qi)
                        {:title "SSE Extracted" :body-html "<p>body</p>"})
          (let [resp (GET port (str "/queue/" qid "/row"))]
            (is (sse? resp))
            (is (str/includes? (:body resp) "SSE Extracted"))
            (is (not (str/includes? (:body resp) "data-on-interval")) "the poll attribute is gone")))

        (testing "POST /queue/:id/read patches the reader controls in place"
          (let [resp (POST port (str "/queue/" qid "/read"))]
            (is (sse? resp))
            (is (str/includes? (:body resp) "reader-controls"))
            (is (str/includes? (:body resp) (str "/queue/" qid "/unread")) "the toggle flips")
            (is (= "read" (:queue-items/state (crud/by-id ds :queue-items qid))))))

        (testing "POST /queue/:id/tags patches the tag editor and clears $tag"
          (let [resp (POST port (str "/queue/" qid "/tags") "label=focus")]
            (is (sse? resp))
            (is (str/includes? (:body resp) "reader-tags"))
            (is (str/includes? (:body resp) ">focus<") "the new tag chip renders")
            (is (str/includes? (:body resp) "datastar-patch-signals") "the bound $tag signal is cleared")))

        (testing "POST /queue/:id/archive removes the row in place"
          (let [resp (POST port (str "/queue/" qid "/archive"))]
            (is (sse? resp))
            (is (str/includes? (:body resp) "datastar-patch-elements"))
            (is (str/includes? (:body resp) "remove"))
            (is (str/includes? (:body resp) (str "#q-" qid)))
            (is (= "archived" (:queue-items/state (crud/by-id ds :queue-items qid))))))

        (testing "GET /queue/:id/row for a now-archived item removes the stale row (and its poll)"
          (let [resp (GET port (str "/queue/" qid "/row"))]
            (is (sse? resp))
            (is (str/includes? (:body resp) "remove"))
            (is (str/includes? (:body resp) (str "#q-" qid)))))))))
