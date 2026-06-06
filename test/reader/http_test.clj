(ns reader.http-test
  "Integration tests for the HTTP layer. Brings up the full test system
   (`reader.test-support.setup/with-system`, which also binds http-kit on an
   ephemeral port) and seeds it, then drives the real ring handler with
   ring-mock requests. POST requests carry a real form-encoded body, so the
   parameters middleware is exercised alongside the handlers."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.db.crud :as crud]
            [reader.dev.seed :as seed]
            [reader.test-support.setup :refer [with-system]]
            [ring.mock.request :as mock]))

(deftest routes-via-handler
  (with-system [system]
    (seed/seed! (:reader.db/datasource system))
    (let [handler (:reader.concerns.reitit/ring-handler system)]

      (testing "GET / renders the reading list from seeded data"
        (let [{:keys [status headers body]} (handler (mock/request :get "/"))]
          (is (= 200 status))
          (is (re-find #"(?i)text/html" (get headers "content-type")))
          (is (re-find #"Your reading list" body))
          (is (re-find #"The White Album" body) "an article title shows")
          (is (re-find #"Attention Is All You Need" body) "a paper title shows")
          (is (re-find #"Joan Didion" body) "an author byline shows")
          (is (re-find #"/authors/joan-didion" body) "author names link to their page")))

      (testing "GET /authors lists authors"
        (let [{:keys [status body]} (handler (mock/request :get "/authors"))]
          (is (= 200 status))
          (is (re-find #"Joan Didion" body))
          (is (re-find #"/authors/joan-didion" body))))

      (testing "GET /authors/:slug shows the author and their affiliation"
        (let [{:keys [status body]} (handler (mock/request :get "/authors/joan-didion"))]
          (is (= 200 status))
          (is (re-find #"Joan Didion" body))
          (is (re-find #"(?i)writes for" body))
          (is (re-find #"The New Yorker" body) "the author's affiliation shows")))

      (testing "GET /authors/:slug 404s for an unknown author"
        (let [{:keys [status]} (handler (mock/request :get "/authors/nobody"))]
          (is (= 404 status))))

      (testing "GET /affiliations lists sources"
        (let [{:keys [status body]} (handler (mock/request :get "/affiliations"))]
          (is (= 200 status))
          (is (re-find #"The New Yorker" body))
          (is (re-find #"arXiv" body))))

      (testing "GET /health returns ok as text/plain"
        (let [{:keys [status headers body]} (handler (mock/request :get "/health"))]
          (is (= 200 status))
          (is (re-find #"text/plain" (get headers "content-type")))
          (is (= "ok" body))))

      (testing "GET /static/css/tokens.css is served from resources/public"
        (let [{:keys [status body]} (handler (mock/request :get "/static/css/tokens.css"))]
          (is (= 200 status))
          (is (re-find #":root" (slurp body)))))

      (testing "Unknown routes return 404"
        (let [{:keys [status]} (handler (mock/request :get "/no-such-route"))]
          (is (= 404 status)))))))

(deftest article-create-and-delete
  (with-system [system]
    (let [ds       (:reader.db/datasource system)
          handler  (:reader.concerns.reitit/ring-handler system)
          on-home? (fn [needle]
                     (re-find (re-pattern needle) (:body (handler (mock/request :get "/")))))]

      (testing "GET /articles/new renders the form"
        (let [{:keys [status body]} (handler (mock/request :get "/articles/new"))]
          (is (= 200 status))
          (is (re-find #"(?i)<form" body))
          (is (re-find #"canonical-url" body))))

      (testing "POST /articles with valid input creates the article and redirects"
        (let [{:keys [status headers]}
              (handler (mock/request :post "/articles"
                                     {"title"         "A Hand-Added Piece"
                                      "canonical-url" "https://example.com/hand-added"}))]
          (is (= 303 status))
          (is (= "/" (get headers "location")))
          (is (on-home? "A Hand-Added Piece") "the new article shows on the reading list")))

      (testing "POST /articles with a blank title re-renders the form and writes nothing"
        (let [{:keys [status body]}
              (handler (mock/request :post "/articles"
                                     {"title" "" "canonical-url" "https://example.com/rejected"}))]
          (is (= 200 status))
          (is (re-find #"(?i)<form" body))
          (is (nil? (crud/find-1 ds :articles {:canonical-url "https://example.com/rejected"})))))

      (testing "POST /readables/articles/:id/delete removes the article and redirects"
        (let [id (:articles/id (crud/find-1 ds :articles {:canonical-url "https://example.com/hand-added"}))
              {:keys [status headers]} (handler (mock/request :post (str "/readables/articles/" id "/delete")))]
          (is (= 303 status))
          (is (= "/" (get headers "location")))
          (is (not (on-home? "A Hand-Added Piece")) "it is gone from the reading list")))

      (testing "POST /readables/:table/:id/delete removes a non-article readable too"
        (let [id (:papers/id (crud/create! ds :papers {:title          "A Paper To Remove"
                                                       :pdf-object-key "papers/remove.pdf"}))
              {:keys [status headers]} (handler (mock/request :post (str "/readables/papers/" id "/delete")))]
          (is (= 303 status))
          (is (= "/" (get headers "location")))
          (is (nil? (crud/find-1 ds :papers {:id id})) "the paper row is gone")))

      (testing "POST /readables/:table/:id/delete with a non-uuid id 404s"
        (let [{:keys [status]} (handler (mock/request :post "/readables/articles/not-a-uuid/delete"))]
          (is (= 404 status))))

      (testing "POST /readables/:table/:id/delete refuses a table that isn't a readable"
        (let [{:keys [status]} (handler (mock/request :post (str "/readables/users/" (random-uuid) "/delete")))]
          (is (= 404 status)))))))
