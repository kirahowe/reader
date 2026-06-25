(ns reader.eval.handlers
  "The evals app's request handlers — lean glue (read input → domain → render),
   one Integrant component each, grouped here per the reader's convention. The
   Workbench verdict POSTs write a golden label and redirect to the next case;
   everything else reads. The whole app is operator-gated by reader.eval.middleware."
  (:require [clojure.string :as str]
            [integrant.core :as ig]
            [reader.eval.inspect :as inspect]
            [reader.eval.labels :as labels]
            [reader.eval.metrics :as metrics]
            [reader.eval.queue :as queue]
            [reader.eval.runs :as runs]
            [reader.eval.ui :as ui]
            [reader.util.slug :as slug]
            [reader.web.response :as response]
            [starfederation.datastar.clojure.adapter.http-kit :as hk]
            [starfederation.datastar.clojure.api :as d*]))

;; ── helpers ──────────────────────────────────────────────────────────────────

(defn- operator [req] (some-> req :user :users/email))

(defn- multi
  "A request param that may repeat → a seq (single value → one-element seq)."
  [params k]
  (let [v (get params k)]
    (cond (sequential? v) v (some? v) [v] :else [])))

(defn- added-slugs
  "A comma-separated 'add' field → a set of tag/author slugs."
  [s]
  (->> (str/split (or s "") #",") (map str/trim) (remove str/blank?) (map slug/slugify) (set)))

(defn- enc [s] (java.net.URLEncoder/encode (str s) "UTF-8"))

(defn- skip-ids [req] (set (multi (:query-params req) "skip")))

(defn- skip-href
  "The skip link for the current case: the queue URL carrying the accumulated
   skips plus this case's id, so each skip advances past one more case."
  [pipeline skips id]
  (str "/workbench?p=" pipeline
       (apply str (map #(str "&skip=" (enc %)) (conj skips (str id))))))

(defn- next-fragment
  "The #wb fragment for the next case in `pipeline`'s queue (post-verdict, so no
   skips to carry), or the caught-up panel when the queue is drained."
  [ds pipeline]
  (if (= pipeline :extraction)
    (if-let [c (queue/extraction-next ds)]
      (ui/extraction-fragment (assoc c :skip-href "/workbench?p=extraction"))
      (ui/caught-up-fragment :extraction))
    (if-let [c (queue/tagging-next ds)]
      (ui/tagging-fragment (assoc c :skip-href "/workbench?p=tagging"))
      (ui/caught-up-fragment :tagging))))

(defn- sse-patch
  "An SSE response that patches one fragment (by element id) and closes — the
   shape every Datastar write in this app uses."
  [req fragment]
  (hk/->sse-response req
                     {hk/on-open (fn [sse]
                                   (d*/patch-elements! sse (ui/fragment-html fragment))
                                   (d*/close-sse! sse))}))

(defn- verdict-response
  "After writing a verdict: a Datastar request gets an SSE patch of the next
   case (snappy, no reload); a plain form post gets the existing redirect."
  [req ds pipeline redirect-to]
  (if (d*/datastar-request? req)
    (sse-patch req (next-fragment ds pipeline))
    (response/see-other redirect-to)))

;; ── landing ──────────────────────────────────────────────────────────────────

(defmethod ig/init-key :reader.eval.handlers/home [_ _]
  (fn [_req] (response/html (ui/home))))

;; ── overview ─────────────────────────────────────────────────────────────────

(defmethod ig/init-key :reader.eval.handlers/overview [_ {:keys [datasource]}]
  (fn [req]
    (if (= "extraction" (get-in req [:query-params "p"]))
      (response/html (ui/extraction-overview
                      (let [{:keys [byline source]} (labels/extraction-score datasource)]
                        (assoc (metrics/extraction-overview datasource)
                               :byline byline :source source :labeled (:labeled byline)))))
      (response/html (ui/tagging-overview
                      (merge (metrics/tagging-overview datasource)
                             (labels/tagging-score datasource)))))))

;; ── workbench ────────────────────────────────────────────────────────────────

(defmethod ig/init-key :reader.eval.handlers/workbench [_ {:keys [datasource]}]
  (fn [req]
    (let [skips (skip-ids req)]
      (if (= "extraction" (get-in req [:query-params "p"]))
        (if-let [c (queue/extraction-next datasource {:exclude skips})]
          (response/html (ui/extraction-workbench (assoc c :skip-href (skip-href "extraction" skips (:subject-url c)))))
          (response/html (ui/workbench-empty :extraction)))
        (if-let [c (queue/tagging-next datasource {:exclude (into #{} (keep parse-uuid) skips)})]
          (response/html (ui/tagging-workbench (assoc c :skip-href (skip-href "tagging" skips (:readable-id c)))))
          (response/html (ui/workbench-empty :tagging)))))))

(defmethod ig/init-key :reader.eval.handlers/tagging-verdict [_ {:keys [datasource]}]
  (fn [req]
    (let [params (:form-params req)
          rtype  (get params "readable-type")
          rid    (some-> (get params "readable-id") parse-uuid)]
      (when (and rtype rid)
        (let [assigned (labels/production-tag-slugs datasource rtype rid)
              golden   (labels/apply-corrections assigned (set (multi params "wrong"))
                                                 (added-slugs (get params "added")))]
          (labels/record-tagging! datasource {:readable-type rtype :readable-id rid
                                              :golden golden :labeled-by (operator req)})))
      (verdict-response req datasource :tagging "/workbench?p=tagging"))))

(defmethod ig/init-key :reader.eval.handlers/extraction-verdict [_ {:keys [datasource]}]
  (fn [req]
    (let [params (:form-params req)
          url    (get params "subject-url")]
      (when url
        (let [assigned   (labels/production-byline-slugs datasource url)
              golden     (labels/apply-corrections assigned (set (multi params "author-wrong"))
                                                   (added-slugs (get params "added-author")))
              source-ok? (nil? (get params "source-wrong"))]
          (labels/record-extraction! datasource
                                     {:subject-url url
                                      :authors     golden
                                      :source      (when source-ok? (labels/production-source datasource url))
                                      :labeled-by  (operator req)})))
      (verdict-response req datasource :extraction "/workbench?p=extraction"))))

;; ── benchmark runs ────────────────────────────────────────────────────────────

(defmethod ig/init-key :reader.eval.handlers/runs [_ {:keys [datasource]}]
  (fn [req]
    ;; A Datastar GET is the runs page polling itself while a run is in flight
    ;; (see ui/runs-list-fragment); a plain GET is the full page.
    (if (d*/datastar-request? req)
      (sse-patch req (ui/runs-list-fragment (runs/list-runs datasource "tagging")))
      (response/html (ui/runs-index (runs/list-runs datasource "tagging"))))))

(defmethod ig/init-key :reader.eval.handlers/run-benchmark [_ {:keys [datasource complete]}]
  (fn [req]
    (let [params (:form-params req)
          model  (some-> (get params "model") str/trim not-empty)
          rformat (some-> (get params "response-format") str/trim not-empty)
          tagger (runs/tagging-tagger complete {:model model :response-format rformat})
          run    (runs/create-run! datasource
                                   {:model  (if complete (or model "configured") "stub")
                                    :config (cond-> {} rformat (assoc :response-format rformat))})]
      ;; Score off the request thread: a real-model run over the labeled set can
      ;; take a while, so the POST acks immediately and the runs list polls for
      ;; the result. finish-run! records a failure rather than throwing.
      (future (runs/finish-run! datasource (:eval-runs/id run) tagger))
      (if (d*/datastar-request? req)
        (sse-patch req (ui/runs-list-fragment (runs/list-runs datasource "tagging")))
        (response/see-other "/runs")))))

(defmethod ig/init-key :reader.eval.handlers/run-case [_ {:keys [datasource]}]
  (fn [req]
    (if-let [id (some-> (get-in req [:query-params "id"]) parse-uuid)]
      (if-let [d (runs/run-detail datasource id)]
        (response/html (ui/run-detail d))
        (response/not-found "No such run."))
      (response/not-found "Unknown run."))))

;; ── cases (read-only drill-down) ──────────────────────────────────────────────

(defmethod ig/init-key :reader.eval.handlers/tagging-index [_ {:keys [datasource]}]
  (fn [_req] (response/html (ui/tagging-index (inspect/tagging-cases datasource {})))))

(defmethod ig/init-key :reader.eval.handlers/tagging-case [_ {:keys [datasource]}]
  (fn [req]
    (let [type (get-in req [:query-params "type"])
          id   (some-> (get-in req [:query-params "id"]) parse-uuid)]
      (if (and type id)
        (response/html (ui/tagging-detail (inspect/tagging-case datasource type id)))
        (response/not-found "Unknown case.")))))

(defmethod ig/init-key :reader.eval.handlers/extraction-index [_ {:keys [datasource]}]
  (fn [_req] (response/html (ui/extraction-index (inspect/extraction-cases datasource {})))))

(defmethod ig/init-key :reader.eval.handlers/extraction-case [_ {:keys [datasource]}]
  (fn [req]
    (if-let [url (get-in req [:query-params "url"])]
      (response/html (ui/extraction-detail (inspect/extraction-case datasource url) url))
      (response/not-found "Unknown case."))))
