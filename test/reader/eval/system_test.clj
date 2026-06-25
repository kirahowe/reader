(ns reader.eval.system-test
  "End-to-end boot of the evals app against a real embedded Postgres: the system
   wires, the operator gate admits operators and 404s everyone else, and a
   dashboard renders a seeded case. Exercises auth → operator gate → routing →
   render through the actual ring handler."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reader.db.crud :as crud]
            [reader.domain.tags :as tags]
            [reader.eval.test-support :refer [with-eval-system]]
            [reader.ingest.tag-events :as tag-events]
            [reader.test-support.auth :as auth]
            [ring.mock.request :as mock]))

(defn- seed-tagged-article! [ds]
  (let [aid (:articles/id (crud/create! ds :articles
                                        {:title "Attention Is All You Need" :slug "aiayn"
                                         :canonical-url "https://example.com/aiayn"}))
        ml  (:tags/id (crud/create! ds :tags {:slug "ml" :label "machine learning"}))]
    (tags/set-baseline! ds "article" aid [{:tag-id ml :confidence 0.95}])
    (tag-events/record! ds {:readable-type "article" :readable-id aid :outcome :done
                            :model "gpt-4o-mini" :tag-count 1 :duration-ms 100
                            :provenance {:labels ["machine learning"] :vocab-size 1}})
    aid))

(deftest evals-app-boots-and-gates
  (with-eval-system [system]
    (let [ds      (:reader.db/datasource system)
          handler (:reader.concerns.reitit/ring-handler system)]
      (seed-tagged-article! ds)
      (testing "health is public"
        (is (= 200 (:status (handler (mock/request :get "/health"))))))
      (testing "an operator sees the tagging dashboard with the seeded case"
        (let [resp (handler (auth/authed (mock/request :get "/tagging") (auth/token "op@x.test")))]
          (is (= 200 (:status resp)))
          (is (str/includes? (:body resp) "Attention Is All You Need"))))
      (testing "a signed-in non-operator (a plain reader user) gets 404 — no existence leak"
        (let [resp (handler (auth/authed (mock/request :get "/tagging") (auth/token "reader@x.test")))]
          (is (= 404 (:status resp)))))
      (testing "an unauthenticated browser GET is sent to login"
        (is (= 303 (:status (handler (mock/request :get "/tagging")))))))))
