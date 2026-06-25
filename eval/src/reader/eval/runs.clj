(ns reader.eval.runs
  "Non-destructive benchmark runs (ADR 0006): execute the real inference path
   under a config over the labeled set, score the proposals against the golden
   labels, and persist the run — writing only eval_* tables, never the production
   baseline or graph. Lets us track pipeline quality over time and across model/
   prompt changes. Tagging only for now; extraction runs are a later addition.

   A run is created `running`, scored off the request thread, then settled to
   `done` (with counts) or `failed` (with the error) — so a slow real-model run
   acks immediately and a mid-run failure is recorded, not a swallowed 500."
  (:require [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [reader.db.crud :as crud]
            [reader.domain.tags :as tags]
            [reader.eval.inspect :as inspect]
            [reader.eval.scoring :as scoring]
            [reader.ingest.tag :as tag]
            [reader.util.slug :as slug]))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})
(defn- q  [ds m] (jdbc/execute!     ds (sql/format m) opts))
(defn- q1 [ds m] (jdbc/execute-one! ds (sql/format m) opts))

(defn tagging-tagger
  "An infer-tags fn for a run: the real llm-tagger over `complete` when a model
   is configured, else the deterministic stub (so a run works offline)."
  [complete {:keys [model response-format]}]
  (if complete
    (tag/llm-tagger complete (or model "configured") (some-> response-format keyword))
    (tag/stub-tagger)))

(defn- content-for [ds rtype rid]
  (let [s (inspect/readable-summary ds rtype rid)]
    {:title (:title s) :abstract (:abstract s)}))

(defn- golden-by-readable [ds]
  (into {}
        (map (juxt (juxt :readable-type :readable-id) #(set (:tags (:label %)))))
        (q ds {:select [:readable-type :readable-id :label]
               :from   [:eval-labels] :where [:= :feature "tagging"]})))

(defn create-run!
  "Open a `running` run for a feature under `config`, recording the `model` label
   that will appear in the ledger. Settled later by `finish-run!`."
  [ds {:keys [model config]}]
  (crud/create! ds :eval-runs {:feature "tagging" :status "running"
                               :config (or config {}) :model model}))

(defn- score-tagging
  "Run `tagger` over every labeled readable and score its proposals against the
   golden tag set. Pure of the run row — the LLM calls happen here, outside any
   transaction. Returns {:cases [...] :tp :fp :fn :n}."
  [ds tagger]
  (let [golden (golden-by-readable ds)
        vocab  (mapv :label (tags/vocabulary ds))
        cases  (for [[[rtype rid] g] golden]
                 (let [proposed  (mapv :label (:tags (tag/coerce (tagger (content-for ds rtype rid) vocab))))
                       predicted (set (map slug/slugify proposed))]
                   {:readable-type rtype :readable-id rid :proposed proposed :golden g :predicted predicted}))]
    (assoc (scoring/prf cases) :cases cases)))

(defn finish-run!
  "Score `run-id` with `tagger` and settle it: `done` with counts + per-case
   results, or `failed` with the error if inference threw. Returns the settled
   run row. Non-destructive — the only writes are to eval_* tables."
  [ds run-id tagger]
  (try
    (let [{:keys [cases tp fp fn n]} (score-tagging ds tagger)]
      (jdbc/with-transaction [tx ds]
        (doseq [c cases]
          (crud/create! tx :eval-run-results {:run-id        run-id
                                              :readable-type (:readable-type c)
                                              :readable-id   (:readable-id c)
                                              :proposed      (:proposed c)}))
        (crud/update! tx :eval-runs run-id {:status "done" :n n :tp tp :fp fp :fn fn})))
    (catch Throwable t
      (log/error t "benchmark run failed" {:run-id run-id})
      (crud/update! ds :eval-runs run-id {:status "failed" :error (.getMessage t)}))))

(defn run-tagging!
  "Create a run and score it synchronously, returning the settled row. The
   handler runs `finish-run!` off-thread; this is the synchronous core used
   directly in tests."
  [ds tagger {:keys [model config]}]
  (finish-run! ds (:eval-runs/id (create-run! ds {:model model :config config})) tagger))

(defn list-runs
  "Runs for a feature, newest-first. A settled (`done`) run carries derived
   precision/recall/F1; a `running`/`failed` run carries only its status."
  [ds feature]
  (mapv #(cond-> % (= "done" (:status %)) (merge (scoring/ratios %)))
        (q ds {:select   [:id :feature :model :status :error :n :tp :fp :fn :created-at]
               :from     [:eval-runs] :where [:= :feature feature]
               :order-by [[:created-at :desc]]})))

(defn run-detail
  "A run with its derived scores and per-case proposals, each re-scored against
   the current golden labels."
  [ds run-id]
  (when-let [run (q1 ds {:select [:*] :from [:eval-runs] :where [:= :id run-id]})]
    (let [golden  (golden-by-readable ds)
          results (q ds {:select [:readable-type :readable-id :proposed]
                         :from   [:eval-run-results] :where [:= :run-id run-id]})
          cases   (for [{:keys [readable-type readable-id proposed]} results]
                    (let [g (get golden [readable-type readable-id] #{})]
                      {:title    (:title (inspect/readable-summary ds readable-type readable-id))
                       :proposed (mapv (fn [p] {:label p :correct? (contains? g (slug/slugify p))}) proposed)
                       :golden   (vec (sort g))}))]
      (cond-> (assoc run :cases (vec cases))
        (= "done" (:status run)) (merge (scoring/ratios run))))))
