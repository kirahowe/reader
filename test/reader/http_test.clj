(ns reader.http-test
  "Integration tests for the HTTP layer. Brings up the full test system
   (`reader.test-support.setup/with-system`, which also binds http-kit on an
   ephemeral port) and seeds it, then drives the real ring handler with
   ring-mock requests. The app routes are auth-protected, so requests to them
   carry a session cookie (`reader.test-support.test-auth/authed`); `/health` and
   `/static` are public and don't. POSTs carry a real form-encoded body, so the
   parameters middleware is exercised alongside the handlers. The lone
   over-the-wire test, `end-to-end-server-binds-and-serves`, instead reaches the
   bound ephemeral port with a real HTTP client, proving http-kit serves and the
   middleware stack runs in the actual server path."
  (:require [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as http]
            [reader.db.crud :as crud]
            [reader.dev.seed :as seed]
            [reader.reading :as reading]
            [reader.test-support.auth :as test-auth]
            [reader.test-support.setup :refer [with-system]]
            [ring.mock.request :as mock])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(defn- GET
  "A real over-the-wire GET to the running test server. HttpClient's default
   redirect policy is NEVER, so a redirect is observed rather than followed."
  [port path]
  (let [req (-> (HttpRequest/newBuilder)
                (.uri (URI/create (str "http://localhost:" port path)))
                (.GET)
                (.build))]
    (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/ofString))))

(deftest routes-via-handler
  (with-system [system]
    (let [ds      (:reader.db/datasource system)
          handler (:reader.concerns.reitit/ring-handler system)]
      (seed/seed! ds)
      ;; Home is the signed-in user's queue, not the global catalog. Provision the
      ;; test user (first authed request), then queue the seeded readables for them
      ;; so the page has something to render.
      (-> (mock/request :get "/") test-auth/authed handler)
      (let [uid (:users/id (crud/find-1 ds :users {:email test-auth/invited-email}))]
        (reading/enqueue! ds uid "article"
                          (:articles/id (crud/find-1 ds :articles {:canonical-url "https://www.newyorker.com/the-white-album"})))
        (reading/enqueue! ds uid "paper"
                          (:papers/id (crud/find-1 ds :papers {:title "Attention Is All You Need"})))
        (reading/enqueue! ds uid "newsletter_issue"
                          (:newsletter-issues/id (crud/find-1 ds :newsletter-issues {:subject "ACT links for the week"}))))

      (testing "GET / renders the signed-in user's reading queue"
        (let [{:keys [status headers body]} (-> (mock/request :get "/") test-auth/authed handler)]
          (is (= 200 status))
          (is (re-find #"(?i)text/html" (get headers "content-type")))
          (is (re-find #"Your reading list" body))
          (is (re-find #"The White Album" body) "an article title shows")
          (is (re-find #"href=\"/queue/[^\"]+\">The White Album" body) "titles link to the reader view")
          (is (re-find #"Attention Is All You Need" body) "a paper title shows")
          (is (re-find #"Joan Didion" body) "an author byline shows")
          (is (re-find #"/authors/joan-didion" body) "author names link to their page")
          (is (re-find #"action=\"/queue/" body) "each item has an archive control")
          (is (re-find #"action=\"/logout\"" body) "a sign-out control is present")))

      (testing "GET /authors lists authors"
        (let [{:keys [status body]} (-> (mock/request :get "/authors") test-auth/authed handler)]
          (is (= 200 status))
          (is (re-find #"Joan Didion" body))
          (is (re-find #"/authors/joan-didion" body))))

      (testing "GET /authors/:slug shows the author and their affiliation"
        (let [{:keys [status body]} (-> (mock/request :get "/authors/joan-didion") test-auth/authed handler)]
          (is (= 200 status))
          (is (re-find #"Joan Didion" body))
          (is (re-find #"(?i)writes for" body))
          (is (re-find #"The New Yorker" body) "the author's affiliation shows")))

      (testing "GET /authors/:slug 404s for an unknown author"
        (is (= 404 (:status (-> (mock/request :get "/authors/nobody") test-auth/authed handler)))))

      (testing "GET /affiliations lists sources"
        (let [{:keys [status body]} (-> (mock/request :get "/affiliations") test-auth/authed handler)]
          (is (= 200 status))
          (is (re-find #"The New Yorker" body))
          (is (re-find #"arXiv" body))))

      (testing "GET /health returns ok as text/plain"
        (let [{:keys [status headers body]} (-> (mock/request :get "/health") handler)]
          (is (= 200 status))
          (is (re-find #"text/plain" (get headers "content-type")))
          (is (= "ok" body))))

      (testing "GET /static/css/tokens.css is served from resources/public"
        (let [{:keys [status body]} (-> (mock/request :get "/static/css/tokens.css") handler)]
          (is (= 200 status))
          (is (re-find #":root" (slurp body)))))

      (testing "Unknown routes return 404"
        (is (= 404 (:status (-> (mock/request :get "/no-such-route") handler))))))))

(deftest article-create-and-archive
  (with-system [system]
    (let [ds       (:reader.db/datasource system)
          handler  (:reader.concerns.reitit/ring-handler system)
          on-home? (fn [needle]
                     (re-find (re-pattern needle)
                              (:body (-> (mock/request :get "/") test-auth/authed handler))))]

      (testing "GET /articles/new renders the form"
        (let [{:keys [status body]} (-> (mock/request :get "/articles/new") test-auth/authed handler)]
          (is (= 200 status))
          (is (re-find #"(?i)<form" body))
          (is (re-find #"canonical-url" body))))

      (testing "POST /articles with valid input creates the article, queues it, and redirects"
        (let [{:keys [status headers]}
              (-> (mock/request :post "/articles"
                                {"title"         "A Hand-Added Piece"
                                 "canonical-url" "https://example.com/hand-added"})
                  test-auth/authed handler)]
          (is (= 303 status))
          (is (= "/" (get headers "location")))
          (is (on-home? "A Hand-Added Piece") "the new article shows on the reading list")))

      (testing "POST /articles with a duplicate URL re-renders the form, no 500"
        ;; The create+enqueue transaction must survive create!'s caught unique
        ;; violation: the article insert rolls back and we re-render, not 500.
        (let [{:keys [status body]}
              (-> (mock/request :post "/articles"
                                {"title"         "A Hand-Added Piece, Again"
                                 "canonical-url" "https://example.com/hand-added"})
                  test-auth/authed handler)]
          (is (= 200 status) "not a 500 from the aborted insert")
          (is (re-find #"(?i)already exists" body) "the duplicate-URL error shows")))

      (testing "POST /articles with an unknown affiliation-id re-renders the form, no 500"
        ;; A well-formed but non-existent affiliation-id passes validation and
        ;; trips the FK on insert; like the duplicate-URL case it must come back
        ;; as a visible form error, not a 500 from the aborted insert.
        (let [{:keys [status body]}
              (-> (mock/request :post "/articles"
                                {"title"          "Orphaned Submission"
                                 "canonical-url"  "https://example.com/orphan-http"
                                 "affiliation-id" "00000000-0000-0000-0000-0000000000ff"})
                  test-auth/authed handler)]
          (is (= 200 status) "not a 500 from the aborted insert")
          (is (re-find #"(?i)unknown source" body) "the unknown-affiliation error shows")))

      (testing "POST /articles with a blank title re-renders the form and writes nothing"
        (let [{:keys [status body]}
              (-> (mock/request :post "/articles"
                                {"title" "" "canonical-url" "https://example.com/rejected"})
                  test-auth/authed handler)]
          (is (= 200 status))
          (is (re-find #"(?i)<form" body))
          (is (nil? (crud/find-1 ds :articles {:canonical-url "https://example.com/rejected"})))))

      (testing "POST /queue/:id/archive removes the item from the reading list"
        (let [uid (:users/id (crud/find-1 ds :users {:email test-auth/invited-email}))
              aid (:articles/id (crud/find-1 ds :articles {:canonical-url "https://example.com/hand-added"}))
              qid (:queue-items/id (crud/find-1 ds :queue-items {:user-id uid :readable-id aid}))
              {:keys [status headers]} (-> (mock/request :post (str "/queue/" qid "/archive"))
                                           test-auth/authed handler)]
          (is (= 303 status))
          (is (= "/" (get headers "location")))
          (is (not (on-home? "A Hand-Added Piece")) "it is gone from the reading list")))

      (testing "POST /queue/:id/archive with a non-uuid id 404s"
        (is (= 404 (:status (-> (mock/request :post "/queue/not-a-uuid/archive")
                                test-auth/authed handler)))))

      (testing "POST /queue/:id/archive refuses another user's item, leaving it untouched"
        (let [other (:users/id (crud/create! ds :users {:email "intruder@x.test"}))
              paper (:papers/id (crud/create! ds :papers {:title "Theirs" :pdf-object-key "papers/theirs.pdf"}))
              qid   (:queue-items/id (reading/enqueue! ds other "paper" paper))]
          (is (= 404 (:status (-> (mock/request :post (str "/queue/" qid "/archive"))
                                  test-auth/authed handler))))
          (is (= "unread" (:queue-items/state (crud/find-1 ds :queue-items {:id qid})))
              "their queue item is not archived"))))))

(deftest csrf-gate-is-mounted
  ;; Proves the CSRF middleware actually sits in front of the stack, not just
  ;; that its logic is correct (csrf-test covers the logic). test.edn pins
  ;; site-origin to http://localhost.
  (with-system [system]
    (let [handler (:reader.concerns.reitit/ring-handler system)]

      (testing "a cross-origin POST is rejected before reaching the handler"
        (let [{:keys [status body]} (-> (mock/request :post "/articles"
                                                      {"title"         "Should Not Land"
                                                       "canonical-url" "https://example.com/csrf"})
                                        (mock/header "origin" "https://evil.test")
                                        test-auth/authed handler)]
          (is (= 403 status) "blocked by CSRF even with a valid session")
          (is (= "bad origin" body))))

      (testing "a same-origin POST passes the gate through to the handler"
        (let [{:keys [status]} (-> (mock/request :post "/articles"
                                                 {"title"         "A Same-Origin Piece"
                                                  "canonical-url" "https://example.com/same-origin"})
                                   (mock/header "origin" "http://localhost")
                                   test-auth/authed handler)]
          (is (= 303 status) "matching origin reaches the create handler and redirects"))))))

(deftest end-to-end-server-binds-and-serves
  ;; Everything above drives the ring handler directly; this is the one test
  ;; that goes over a real socket to the port http-kit actually bound.
  (with-system [system]
    (let [port (http/server-port (:reader.concerns/http-kit system))]
      (is (pos? port) "http-kit bound to an ephemeral port")

      (testing "GET /health over the wire"
        (let [resp (GET port "/health")]
          (is (= 200 (.statusCode resp)))
          (is (= "ok" (.body resp)))))

      (testing "an unauthenticated GET / redirects to /login over the wire"
        (let [resp (GET port "/")]
          (is (= 303 (.statusCode resp)))
          (is (= "/login" (.. resp headers (firstValue "location") (orElse nil)))))))))

(deftest reader-view-and-state
  (with-system [system]
    (let [ds      (:reader.db/datasource system)
          handler (:reader.concerns.reitit/ring-handler system)]
      (seed/seed! ds)
      ;; Provision the test user (allowed@x.test — distinct from the seeded users,
      ;; so its queue starts empty), then queue one readable of each type for it.
      (-> (mock/request :get "/") test-auth/authed handler)
      (let [uid   (:users/id (crud/find-1 ds :users {:email test-auth/invited-email}))
            art   (:articles/id (crud/find-1 ds :articles {:canonical-url "https://www.newyorker.com/the-white-album"}))
            pap   (:papers/id (crud/find-1 ds :papers {:title "Attention Is All You Need"}))
            issue (:newsletter-issues/id (crud/find-1 ds :newsletter-issues {:subject "ACT links for the week"}))
            qa    (:queue-items/id (reading/enqueue! ds uid "article" art))
            qp    (:queue-items/id (reading/enqueue! ds uid "paper" pap))
            qn    (:queue-items/id (reading/enqueue! ds uid "newsletter_issue" issue))
            GET   (fn [path] (-> (mock/request :get path) test-auth/authed handler))
            POST  (fn [path] (-> (mock/request :post path) test-auth/authed handler))]

        (testing "GET /queue/:id renders an article reader view (byline, source, original link, control)"
          (let [{:keys [status body]} (GET (str "/queue/" qa))]
            (is (= 200 status))
            (is (re-find #"The White Album" body) "the title shows")
            (is (re-find #"Joan Didion" body) "the byline shows")
            (is (re-find #"The New Yorker" body) "the source shows")
            (is (re-find #"(?i)not available in the reader" body) "the placeholder body shows")
            (is (re-find #"newyorker\.com/the-white-album" body) "links to the original")
            (is (re-find #"action=\"/queue/[^\"]+/read\"" body) "a mark-read control is present"))
          (let [row (crud/by-id ds :queue-items qa)]
            (is (= "reading" (:queue-items/state row)) "the first open promotes it to reading")
            (is (some? (:queue-items/started-at row)) "and stamps started_at")))

        (testing "GET /queue/:id renders a paper reader view with DOI and arXiv links"
          (let [{:keys [status body]} (GET (str "/queue/" qp))]
            (is (= 200 status))
            (is (re-find #"Attention Is All You Need" body))
            (is (re-find #"doi\.org/10\.48550" body) "a DOI link")
            (is (re-find #"arxiv\.org/abs/1706\.03762" body) "an arXiv link")))

        (testing "GET /queue/:id renders a newsletter reader view with its stored body"
          (let [{:keys [status body]} (GET (str "/queue/" qn))]
            (is (= 200 status))
            (is (re-find #"ACT links for the week" body) "the subject shows")
            (is (re-find #"This week" body) "the sanitized newsletter body renders")
            (is (not (re-find #"(?i)not available in the reader" body)) "not the placeholder")))

        (testing "POST /queue/:id/read marks it read and stays on the reader view"
          (let [{:keys [status headers]} (POST (str "/queue/" qa "/read"))]
            (is (= 303 status))
            (is (= (str "/queue/" qa) (get headers "location")) "redirects back to the reader view"))
          (is (= "read" (:queue-items/state (crud/by-id ds :queue-items qa))) "the change is persisted")
          (is (re-find #"action=\"/queue/[^\"]+/unread\"" (:body (GET (str "/queue/" qa))))
              "the control flips to mark-unread"))

        (testing "POST /queue/:id/unread returns it to unread"
          (is (= 303 (:status (POST (str "/queue/" qa "/unread")))))
          (is (= "unread" (:queue-items/state (crud/by-id ds :queue-items qa)))))

        (testing "the reader view 404s for a missing or non-uuid id"
          (is (= 404 (:status (GET (str "/queue/" (random-uuid))))) "no such item")
          (is (= 404 (:status (GET "/queue/not-a-uuid"))) "a non-uuid id"))

        (testing "another user's item is unreadable and untouchable (per-user gating)"
          (let [other (:users/id (crud/create! ds :users {:email "intruder@x.test"}))
                their (:queue-items/id (reading/enqueue! ds other "article" art))]
            (is (= 404 (:status (GET (str "/queue/" their)))) "GET 404s")
            (is (= 404 (:status (POST (str "/queue/" their "/read")))) "POST 404s")
            (is (= "unread" (:queue-items/state (crud/by-id ds :queue-items their)))
                "their item is left untouched")))))))
