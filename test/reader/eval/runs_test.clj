(ns reader.eval.runs-test
  "Benchmark runs against a real embedded Postgres: a run executes the (stub)
   inference path over the labeled set, scores its proposals against the golden
   tags, and persists a scored run — writing only eval_* tables."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reader.db.crud :as crud]
            [reader.eval.labels :as labels]
            [reader.eval.runs :as runs]
            [reader.eval.test-support :refer [with-eval-system]]
            [reader.ingest.tag :as tag]
            [reader.test-support.auth :as auth]
            [reader.util.slug :as slug]
            [ring.mock.request :as mock]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-9))

(deftest tagging-run-test
  (with-eval-system [system]
    (let [ds  (:reader.db/datasource system)
          aid (:articles/id (crud/create! ds :articles {:title "Machine Learning" :slug "ml"
                                                        :canonical-url "https://x/ml"}))]
      ;; golden {machine, databases}; the stub proposes {machine, learning} from the title.
      (labels/record-tagging! ds {:readable-type "article" :readable-id aid
                                  :golden #{"machine" "databases"} :labeled-by "op"})
      (let [run (runs/run-tagging! ds (tag/stub-tagger) {:model "stub"})]
        (testing "persists a scored run over the labeled set (non-destructive)"
          (is (= 1 (:eval-runs/n run)))
          ;; predicted {machine, learning} vs golden {machine, databases}
          (is (= [1 1 1] [(:eval-runs/tp run) (:eval-runs/fp run) (:eval-runs/fn run)]))
          ;; the run touched no production tables
          (is (empty? (crud/find-many ds :readable-tags))))
        (testing "list-runs derives precision/recall/F1"
          (let [[r] (runs/list-runs ds "tagging")]
            (is (= "stub" (:model r)))
            (is (close? 0.5 (:precision r)))
            (is (close? 0.5 (:recall r)))))
        (testing "run-detail carries the per-case proposals re-scored vs labels"
          (let [d (runs/run-detail ds (:eval-runs/id run))]
            (is (close? 0.5 (:f1 d)))
            (is (= 1 (count (:cases d))))
            (let [proposed (:proposed (first (:cases d)))]
              (is (= #{"machine" "learning"} (set (map (comp slug/slugify :label) proposed))))
              ;; "machine" is in golden, "learning" is not
              (is (= {"machine" true "learning" false}
                     (into {} (map (juxt :label :correct?)) proposed))))))))))

(defn- wait-settled
  "Poll until no run is still running (the benchmark scores on a background
   thread; the stub settles in ms). Returns the runs, newest-first."
  [ds]
  (loop [n 0]
    (let [runs (runs/list-runs ds "tagging")]
      (cond
        (and (seq runs) (every? #(not= "running" (:status %)) runs)) runs
        (> n 300) (throw (ex-info "runs did not settle" {:runs runs}))
        :else (do (Thread/sleep 10) (recur (inc n)))))))

(deftest run-benchmark-endpoint-test
  (with-eval-system [system]
    (let [ds  (:reader.db/datasource system)
          h   (:reader.concerns.reitit/ring-handler system)
          aid (:articles/id (crud/create! ds :articles {:title "Deep Learning" :slug "dl"
                                                        :canonical-url "https://x/dl"}))]
      (labels/record-tagging! ds {:readable-type "article" :readable-id aid
                                  :golden #{"deep" "learning"} :labeled-by "op"})
      (testing "POST /runs starts a benchmark with the chosen variant and redirects"
        (let [resp (h (auth/authed (mock/request :post "/runs" {"response-format" "json-object"})
                                   (auth/token "op@x.test")))]
          (is (= 303 (:status resp)))
          (is (= "/runs" (get-in resp [:headers "location"])))))
      (let [[run] (wait-settled ds)]
        (testing "the run settles to done (stub, since no model is configured here)"
          (is (= "done" (:status run)))
          (is (= "stub" (:model run))))
        (testing "the chosen variant is recorded on the run, non-destructively"
          (is (= "json-object" (:response-format (:config (runs/run-detail ds (:id run))))))
          (is (empty? (crud/find-many ds :readable-tags))))
        (testing "the runs page shows it"
          (let [resp (h (auth/authed (mock/request :get "/runs") (auth/token "op@x.test")))]
            (is (= 200 (:status resp)))
            (is (str/includes? (:body resp) "stub"))))))))
