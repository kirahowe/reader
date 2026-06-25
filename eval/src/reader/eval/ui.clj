(ns reader.eval.ui
  "Server-rendered pages for the evals app (ADR 0006). Layered on the reader
   tokens (tokens.css) + shared chrome (main.css), plus its own eval.css.

   Design: a case read like an editor's proof. The content title keeps the
   book's serif voice; everything the pipeline reports — section labels, metrics,
   provenance — speaks in a quiet Inter instrument voice, so they never compete.
   The recurring device is the confidence meter (a gauge, not a bare number).

   Three surfaces, each scoped to a pipeline: Overview (aggregate dashboard),
   Workbench (fast keyboard labeling), Cases (read-only drill-down). Pure: each
   fn takes the maps reader.eval.* return and yields HTML."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [reader.ui.components :as c]))

;; ── shell ──────────────────────────────────────────────────────────────────

(defn- nav-link [href label active? key]
  [:a (cond-> {:href href} (= active? key) (assoc :aria-current "page")) label])

(defn- topbar [active]
  [:header.topbar
   [:div.topbar-inner
    [:a.brand {:href "/"}
     [:span.brand-mark c/icon-book]
     [:span.brand-word "Reader · evals"]]
    [:nav.topnav
     (nav-link "/overview" "Overview" active :overview)
     (nav-link "/workbench" "Workbench" active :workbench)
     (nav-link "/runs" "Runs" active :runs)
     (nav-link "/tagging" "Cases" active :cases)
     [:form.logout {:method "post" :action "/logout"}
      (c/button {:type "submit" :variant :link} "Sign out")]]]])

(defn- page [title active content]
  (str "<!DOCTYPE html>\n"
       (h/html
        {:mode :html}
        [:html {:lang "en"}
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:title title]
          [:link {:rel "stylesheet" :href "/static/css/tokens.css"}]
          [:link {:rel "stylesheet" :href "/static/css/main.css"}]
          [:link {:rel "stylesheet" :href "/static/css/eval.css"}]
          ;; Datastar: server-authoritative reactivity over SSE. Workbench verdicts
          ;; and run progress patch the DOM from the server (reader.eval.handlers).
          [:script {:type "module" :src "/static/vendor/datastar.js"}]]
         [:body (topbar active) [:main.eval-main content]]])))

;; ── shared pieces ────────────────────────────────────────────────────────────

(defn- pct [ratio] (str (Math/round (* 100.0 (double ratio))) "%"))

(defn- conf
  "A nullable score at two decimals, or an em dash."
  [x]
  (if (number? x) (format "%.2f" (double x)) "—"))

(defn- meter
  "The signature device: confidence (0..1) as a gauge. Gold below 0.6. nil (no
   score recorded) renders nothing."
  [score]
  (when (number? score)
    [:div {:class (str "meter" (when (< score 0.6) " is-low"))}
     [:i {:style (str "width:" (pct score))}]]))

(defn- status [outcome]
  [:span {:class (str "status" (case outcome
                                 "failed"  " is-failed"
                                 "skipped" " is-skipped"
                                 ""))}
   (or outcome "—")])

(defn- tile
  ([label value] (tile label value nil))
  ([label value extra]
   [:div.tile [:span.label label] [:span.value value] extra]))

(defn- cell-meter [score]
  [:span.cell-meter (meter score) [:span.num (conf score)]])

(defn- kbd [k] [:kbd.kbd k])

(defn- toggle-hrefs
  "The [tagging extraction] hrefs the pipeline toggle points at, per surface, so
   the toggle stays on the current surface: Overview and Workbench switch pipeline
   in place via ?p=; Cases are distinct routes."
  [surface]
  (case surface
    :overview  ["/overview" "/overview?p=extraction"]
    :workbench ["/workbench" "/workbench?p=extraction"]
    :cases     ["/tagging" "/extraction"]))

(defn- pipeline-toggle
  "Segmented control switching the current surface between pipelines."
  [active surface]
  (let [[tagging-href extraction-href] (toggle-hrefs surface)]
    [:div.seg
     [:a {:class (when (= active :tagging) "is-on")    :href tagging-href} "Tagging"]
     [:a {:class (when (= active :extraction) "is-on") :href extraction-href} "Extraction"]]))

(defn- page-top
  "The heading row: eyebrow + title on the left, the pipeline toggle on the right.
   `surface` (:overview/:workbench/:cases) keeps the toggle on the current page."
  [eyebrow title pipeline surface]
  [:div.page-top
   [:div [:p.eyebrow eyebrow] (when title [:h1.case-title title])]
   (pipeline-toggle pipeline surface)])

;; ── landing ────────────────────────────────────────────────────────────────

(defn home []
  (page "Evals" nil
        (list
         [:p.eyebrow "Pipeline evaluation"]
         [:h1.case-title "Evals"]
         [:p.content-excerpt
          "Inspect what the tagging and extraction pipelines produced on each piece, and where they can do better."]
         [:div.eval-cards
          (c/card [:h2 [:a {:href "/overview"} "Overview →"]]
                  [:p.muted "Coverage, confidence, and accuracy against your labels."])
          (c/card [:h2 [:a {:href "/workbench"} "Workbench →"]]
                  [:p.muted "Label cases fast — judge one at a time, by keyboard."])])))

;; ── overview (aggregate dashboard) ───────────────────────────────────────────

(defn- score [label v]
  [:div.score [:div.label label] [:div.value (conf v)] (meter v)])

(defn- bar-row [label ratio value-str]
  [:div.bar-row
   [:span.bl label]
   [:div.track [:i {:style (str "width:" (pct ratio))}]]
   [:span.bv value-str]])

(defn tagging-overview
  "Aggregate tagging health + accuracy against labels."
  [{:keys [tagged success-rate avg-tags vocab avg-conf
           precision recall f1 labeled top-tags failures]}]
  (page "Overview · Tagging · evals" :overview
        (list
         (page-top "Auto-tagging" "Overview" :tagging :overview)
         [:div.scorecard
          (tile "Tagged" (str tagged))
          (tile "Success" success-rate)
          (tile "Avg tags" (str avg-tags))
          (tile "Vocabulary" (str vocab))
          (tile "Avg conf." (conf avg-conf) (meter avg-conf))]
         [:div.evidence
          [:section
           [:p.eyebrow "Accuracy vs. labels"]
           (if (pos? (or labeled 0))
             (list
              [:div.score-row (score "Precision" precision) (score "Recall" recall) (score "F1" f1)]
              [:p.caption (str "Scored against " labeled " labeled cases. Label more in the Workbench.")])
             [:p.empty "No labels yet — label cases in the Workbench to measure accuracy."])]
          [:section
           [:p.eyebrow "Most-assigned tags"]
           (let [mx (apply max 1 (map :n top-tags))]
             (into [:div.bars] (for [{:keys [label n]} top-tags] (bar-row label (/ n (double mx)) (str n)))))]]
         [:section.block
          [:p.eyebrow "Recent failures"]
          (if (seq failures)
            (into [:ul.fail-list]
                  (for [{:keys [url error]} failures]
                    [:li [:span.err error] [:span.url url]]))
            [:p.empty "No failures recorded."])])))

(defn extraction-overview
  "Aggregate extraction health + accuracy against labels. `byline` is a prf map,
   `source` an accuracy map (from reader.eval.labels/extraction-score)."
  [{:keys [extracted success-rate body-conf entity-conf avg-authors
           byline source labeled coverage failures]}]
  (page "Overview · Extraction · evals" :overview
        (list
         (page-top "Entity extraction" "Overview" :extraction :overview)
         [:div.scorecard
          (tile "Extracted" (str extracted))
          (tile "Success" success-rate)
          (tile "Body conf." (conf body-conf) (meter body-conf))
          (tile "Entity conf." (conf entity-conf) (meter entity-conf))
          (tile "Authors/art." (str avg-authors))]
         [:div.evidence
          [:section
           [:p.eyebrow "Accuracy vs. labels"]
           (if (pos? (or labeled 0))
             (list
              [:div.score-row
               (score "Byline P" (:precision byline))
               (score "Byline R" (:recall byline))
               (score "Source acc." (:accuracy source))]
              [:p.caption (str "Scored against " labeled " labeled cases. Label more in the Workbench.")])
             [:p.empty "No labels yet — label cases in the Workbench to measure accuracy."])]
          [:section
           [:p.eyebrow "Field coverage"]
           (into [:div.bars]
                 (for [{:keys [label pct]} coverage]
                   (bar-row label (/ (or pct 0) 100.0) (str pct "%"))))]]
         [:section.block
          [:p.eyebrow "Recent failures"]
          (if (seq failures)
            (into [:ul.fail-list]
                  (for [{:keys [url error]} failures]
                    [:li [:span.err error] [:span.url url]]))
            [:p.empty "No failures recorded."])])))

;; ── workbench (fast labeling) ────────────────────────────────────────────────

(defn- wb-progress [{:keys [labeled total failed low-conf]}]
  [:div.wb-top
   [:div.progress
    [:div.track [:i {:style (str "width:" (pct (/ labeled (double (max total 1)))))}]]
    [:p.count (str labeled " of ~" total " labeled")]]
   [:span.wb-queue-meta (str failed " failed · " low-conf " low-confidence queued")]])

(defn- wb-actions [skip-href]
  [:div.wb-actions
   (c/button {:variant :primary :type "submit"} "Save & next")
   [:span.keys
    [:span (kbd "↵") " save & next"]
    [:a {:href skip-href :data-skip ""} (kbd "↓") " skip"]]])

;; The keyboard layer is a Datastar expression on the #wb root (no hand-written
;; JS): number keys click the nth flag checkbox, Enter submits (intercepted by
;; the form's @post), ArrowDown follows the skip link; typing in a field is left
;; alone. `s` additionally flags the source on extraction.
(def ^:private wb-keys-common
  (str "if (['INPUT','TEXTAREA'].includes(evt.target.tagName)) return;"
       "if (evt.key === 'Enter') { document.querySelector('#wb form')?.requestSubmit(); return; }"
       "if (evt.key === 'ArrowDown') { document.querySelector('#wb [data-skip]')?.click(); return; }"))

(def ^:private wb-keys-numbers
  (str "const n = Number(evt.key);"
       "if ([1,2,3,4,5,6,7,8,9].includes(n)) document.querySelectorAll('#wb [data-rchip] input')[n-1]?.click();"))

(def ^:private wb-keys-tagging (str wb-keys-common wb-keys-numbers))
(def ^:private wb-keys-extraction
  (str wb-keys-common
       "if (evt.key === 's') { document.querySelector('#wb [data-source-flag] input')?.click(); return; }"
       wb-keys-numbers))

(defn tagging-fragment
  "The #wb case panel — patched in over SSE on each verdict (and morphed in by
   Datastar), so labeling advances without a full reload."
  [{:keys [queue position total readable-type readable-id title excerpt tags skip-href]}]
  [:div#wb {:data-on-keydown__window wb-keys-tagging}
   (wb-progress queue)
   (page-top (str "Auto-tagging · case " position " of " total) nil :tagging :workbench)
   [:h2.wb-case-title title]
   (when excerpt [:p.wb-excerpt excerpt])
   [:form {:method "post" :action "/workbench/tagging"
           :data-on-submit__prevent "@post('/workbench/tagging', {contentType: 'form'})"}
    [:input {:type "hidden" :name "readable-type" :value readable-type}]
    [:input {:type "hidden" :name "readable-id" :value (str readable-id)}]
    [:p.ask "Are these the right tags?"]
    (if (seq tags)
      (into [:div.review-chips]
            (map-indexed (fn [i {:keys [slug label]}]
                           [:label.rchip {:data-rchip "" :data-slug slug}
                            [:input {:type "checkbox" :name "wrong" :value slug}]
                            [:span.k (inc i)] label])
                         tags))
      [:p.empty "No tags were assigned — add any that fit."])
    [:div.add-tag [:input {:type "text" :name "added"
                           :placeholder "+ add a missing tag…" :aria-label "Add a missing tag"}]]
    [:p.hint "Press " (kbd "1") "–" (kbd "9") " to flag a tag wrong · type to add a missing one"]
    (wb-actions skip-href)]])

(defn extraction-fragment
  [{:keys [queue position total subject-url title byline source skip-href]}]
  [:div#wb {:data-on-keydown__window wb-keys-extraction}
   (wb-progress queue)
   (page-top (str "Entity extraction · case " position " of " total) nil :extraction :workbench)
   [:h2.wb-case-title title]
   (when subject-url [:span.wb-case-url [:a {:href subject-url} subject-url]])
   [:form {:method "post" :action "/workbench/extraction"
           :data-on-submit__prevent "@post('/workbench/extraction', {contentType: 'form'})"}
    [:input {:type "hidden" :name "subject-url" :value subject-url}]
    [:p.ask "Is the byline right?"]
    (if (seq byline)
      (into [:div.review-chips]
            (map-indexed (fn [i {:keys [slug name]}]
                           [:label.rchip {:data-rchip "" :data-slug slug}
                            [:input {:type "checkbox" :name "author-wrong" :value slug}]
                            [:span.k (inc i)] name])
                         byline))
      [:p.empty "No authors were resolved — add any that should be here."])
    [:div.add-tag [:input {:type "text" :name "added-author"
                           :placeholder "+ add a missing author…" :aria-label "Add a missing author"}]]
    [:p.ask "Is the source right?"]
    (if source
      [:label.rchip {:data-source-flag "" :data-slug (:slug source)}
       [:input {:type "checkbox" :name "source-wrong" :value "1"}]
       (:name source) (when (:type source) [:span.source-kind (str " · " (:type source))])]
      [:p.empty "No source resolved."])
    [:p.hint "Press " (kbd "1") "–" (kbd "9") " to flag an author · " (kbd "s")
     " to flag the source · type to add an author"]
    (wb-actions skip-href)]])

(defn caught-up-fragment
  "The drained-queue state — also a #wb panel, so the verdict that empties the
   queue can patch it straight in."
  [pipeline]
  [:div#wb
   (page-top (if (= pipeline :extraction) "Entity extraction" "Auto-tagging") "Workbench" pipeline :workbench)
   [:p.empty "All caught up — every case here is labeled. Come back after more ingest, or re-run the pipeline to generate fresh cases."]])

(defn fragment-html
  "Render a #wb fragment to an HTML string for a Datastar SSE patch."
  [fragment]
  (str (h/html fragment)))

(defn tagging-workbench [case]
  (page "Workbench · Tagging · evals" :workbench (tagging-fragment case)))

(defn extraction-workbench [case]
  (page "Workbench · Extraction · evals" :workbench (extraction-fragment case)))

(defn workbench-empty [pipeline]
  (page "Workbench · evals" :workbench (caught-up-fragment pipeline)))

;; ── benchmark runs ────────────────────────────────────────────────────────────

(defn- when-str [ts] (let [s (str ts)] (subs s 0 (min 16 (count s)))))

(defn- run-row [{:keys [id model status error n precision recall f1 created-at]}]
  [:tr
   [:td [:a.title {:href (str "/runs/case?id=" id)} (when-str created-at)]]
   [:td (or model "—")]
   (if (= status "done")
     (list [:td.num n]
           [:td (cell-meter precision)]
           [:td (cell-meter recall)]
           [:td (cell-meter f1)])
     ;; running/failed: no real score yet — span the metric columns with status.
     (list [:td.num "—"]
           [:td {:colspan "3"}
            [:span {:class (str "status" (when (= status "failed") " is-failed"))}
             (if (= status "failed") (str "failed" (when error (str " · " error))) "running…")]]))])

(defn runs-list-fragment
  "The #runs-list panel — patched in over SSE after a run, so a new run appears
   without a reload. While any run is in flight the panel polls itself every 2s
   (Datastar @get /runs); once all runs settle the re-render drops the interval
   attribute, so the poll stops on its own."
  [runs]
  [:div (cond-> {:id "runs-list"}
          (some #(= "running" (:status %)) runs)
          (assoc :data-on-interval__duration.2s "@get('/runs')"))
   (if (empty? runs)
     [:p.empty "No runs yet — label some cases in the Workbench, then run a benchmark."]
     [:table.ledger
      [:thead [:tr [:th "When"] [:th "Model"] [:th "Cases"] [:th "Precision"] [:th "Recall"] [:th "F1"]]]
      (into [:tbody] (map run-row runs))])])

(defn runs-index [runs]
  (page "Runs · evals" :runs
        (list
         [:div.page-top
          [:div [:p.eyebrow "Benchmark runs"] [:h1.case-title "Runs"]]
          ;; The variant is chosen here, per run: a model override (blank = the
          ;; configured default) and the response-format mode. Sent as form data
          ;; so the handler can read it.
          [:form.run-config {:method "post" :action "/runs"
                             :data-on-submit__prevent "@post('/runs', {contentType: 'form'})"}
           [:input {:type "text" :name "model"
                    :placeholder "model — blank uses the configured default" :aria-label "Model"}]
           [:select {:name "response-format" :aria-label "Response format"}
            [:option {:value ""} "format: default"]
            [:option {:value "json-schema"} "json-schema"]
            [:option {:value "json-object"} "json-object"]
            [:option {:value "none"} "none"]]
           (c/button {:variant :primary :type "submit"} "Run benchmark")]]
         [:p.content-excerpt
          "Each run scores a tagging variant against your golden labels, non-destructively. Change the model or response-format and run again to see the delta."]
         (runs-list-fragment runs))))

(defn run-detail [{:keys [model status error n precision recall f1 cases]}]
  (page "Run · evals" :runs
        (list
         (c/back-link "/runs" "All runs")
         [:header.case-head
          [:div.case-topline
           [:p.eyebrow "Benchmark run"]
           [:span {:class (str "status" (when (= status "failed") " is-failed"))} (or status "—")]]
          [:h1.case-title (str "Run over " n " labeled case" (when (not= n 1) "s"))]]
         (when (= status "failed")
           [:p.empty (str "This run failed" (when error (str ": " error)) ".")])
         [:div.scorecard
          (tile "Model" (or model "—"))
          (tile "Cases" (str n))
          (tile "Precision" (conf precision) (meter precision))
          (tile "Recall" (conf recall) (meter recall))
          (tile "F1" (conf f1) (meter f1))]
         [:section.block
          [:p.eyebrow "Per case — proposed vs. golden"]
          (if (seq cases)
            [:table.ledger
             [:thead [:tr [:th "Readable"] [:th "Proposed"] [:th "Golden"]]]
             (into [:tbody]
                   (for [{:keys [title proposed golden]} cases]
                     [:tr
                      [:td.title (or title "(untitled)")]
                      [:td (into [:div.proposed]
                                 (for [{:keys [label correct?]} proposed]
                                   [:span {:class (str "tag" (when-not correct? " dropped"))} label]))]
                      [:td (str/join ", " golden)]]))]
            [:p.empty "No labeled cases to run against yet."])])))

;; ── cases: tagging drill-down ─────────────────────────────────────────────────

(defn- tagging-href [{:keys [readable-type readable-id]}]
  (str "/tagging/case?type=" readable-type "&id=" readable-id))

(defn tagging-index [cases]
  (page "Tagging cases · evals" :cases
        (list
         (page-top "Auto-tagging" "Cases" :tagging :cases)
         (if (empty? cases)
           [:p.empty "No tagging attempts recorded yet."]
           [:table.ledger
            [:thead [:tr [:th "Readable"] [:th "Status"] [:th "Model"] [:th "Tags"] [:th "ms"]]]
            (into [:tbody]
                  (for [{:keys [title outcome model tag-count duration-ms] :as row} cases]
                    [:tr
                     [:td [:a.title {:href (tagging-href row)} (or title "(untitled)")]]
                     [:td (status outcome)]
                     [:td model]
                     [:td.num tag-count]
                     [:td.num duration-ms]]))]))))

(defn- proposals
  "Each proposed label tagged :dropped? when it didn't end up assigned — the
   dedup/threshold story at a glance."
  [proposed assigned]
  (let [kept (into #{} (map (comp str/lower-case str :label)) assigned)]
    (map (fn [l] {:label l :dropped? (not (kept (str/lower-case (str l))))}) proposed)))

(defn- avg-confidence [assigned]
  (when (seq assigned)
    (/ (reduce + (map :confidence assigned)) (count assigned))))

(defn tagging-detail [{:keys [event proposed vocab-size assigned readable]}]
  (let [avg (avg-confidence assigned)]
    (page (str (or (:title readable) "Tagging case") " · evals") :cases
          (list
           (c/back-link "/tagging" "All tagging")
           [:header.case-head
            [:div.case-topline
             [:p.eyebrow "Tagging case"]
             (status (:outcome event))]
            [:h1.case-title (or (:title readable) "Untitled")]]
           [:div.scorecard
            (tile "Model" (or (:model event) "—"))
            (tile "Proposed" (str (:tag-count event)))
            (tile "Assigned" (str (count assigned)))
            (tile "Avg conf." (conf avg) (meter avg))
            (tile "Vocabulary" (str vocab-size))
            (tile "Duration" (str (:duration-ms event) " ms"))]
           [:div.evidence
            [:section
             [:p.eyebrow "Assigned to baseline"]
             (if (seq assigned)
               (into [:ul.tags]
                     (for [{:keys [label confidence]} assigned]
                       [:li.tag-row [:span.name label] [:span.score (conf confidence)] (meter confidence)]))
               [:p.empty "No tags assigned."])]
            [:section
             [:p.eyebrow "Proposed by model"]
             (if (seq proposed)
               (list
                (into [:div.proposed]
                      (for [{:keys [label dropped?]} (proposals proposed assigned)]
                        [:span {:class (str "tag" (when dropped? " dropped"))} label]))
                [:p.caption "Dashed = proposed but not assigned — deduped into an existing tag or below threshold."])
               [:p.empty "No proposals recorded."])]]
           (when-let [ab (:abstract readable)]
             [:section.block
              [:p.eyebrow "Content the model saw"]
              [:p.content-excerpt ab]])))))

;; ── cases: extraction drill-down ──────────────────────────────────────────────

(defn- extraction-href [url]
  (str "/extraction/case?url=" (java.net.URLEncoder/encode (str url) "UTF-8")))

(defn extraction-index [cases]
  (page "Extraction cases · evals" :cases
        (list
         (page-top "Entity extraction" "Cases" :extraction :cases)
         (if (empty? cases)
           [:p.empty "No extraction attempts recorded yet."]
           [:table.ledger
            [:thead [:tr [:th "Source"] [:th "Status"] [:th "Body"] [:th "Entity"] [:th "Authors"]]]
            (into [:tbody]
                  (for [{:keys [url domain outcome body-confidence entity-confidence author-count]} cases]
                    [:tr
                     [:td [:a.title {:href (extraction-href url)} (or domain url)]]
                     [:td (status outcome)]
                     [:td (cell-meter body-confidence)]
                     [:td (cell-meter entity-confidence)]
                     [:td.num author-count]]))]))))

(def ^:private extraction-fields
  [["Title" :title-source] ["Author" :author-source]
   ["Affiliation" :affiliation-source] ["Published" :published-source]])

(defn extraction-detail [{:keys [event provenance article authors affiliation]} url]
  (page (str (or (:title article) "Extraction case") " · evals") :cases
        (list
         (c/back-link "/extraction" "All extraction")
         [:header.case-head
          [:div.case-topline
           [:p.eyebrow "Extraction case"]
           (status (:outcome event))]
          [:h1.case-title (or (:title article) "Untitled")]
          [:span.case-url [:a {:href url} url]]]
         [:div.scorecard
          (tile "Body conf." (conf (:body-confidence event)) (meter (:body-confidence event)))
          (tile "Entity conf." (conf (:entity-confidence event)) (meter (:entity-confidence event)))
          (tile "Authors" (str (:author-count event)))
          (tile "Extractor" (or (:extractor event) "—"))]
         [:div.evidence
          [:section
           [:div.subblock
            [:p.eyebrow "Byline"]
            (if (seq authors)
              (into [:ol.byline] (for [{:keys [name]} authors] [:li name]))
              [:p.empty "No authors resolved into the graph."])]
           [:div.subblock
            [:p.eyebrow "Source"]
            (if affiliation
              [:div
               [:div.source-name (:name affiliation)]
               (when (:type affiliation) [:div.source-kind (:type affiliation)])]
              [:p.empty "No source resolved."])]]
          [:section
           [:p.eyebrow "Field provenance"]
           (into [:dl.kv]
                 (mapcat (fn [[field col]]
                           (let [src (get event col)]
                             [[:dt field] [:dd (if src src [:span.missed "none"])]]))
                         extraction-fields))]]
         (when provenance
           [:details.raw
            [:summary "Raw provenance"]
            [:pre (pr-str provenance)]]))))
