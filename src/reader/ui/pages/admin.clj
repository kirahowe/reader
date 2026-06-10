(ns reader.ui.pages.admin
  "The extraction eval dashboard — server-rendered tables + plain numbers over
   reader.admin's aggregates. No charting library; coverage and rates are shown
   as percentages."
  (:require [reader.ui.layout :as layout]))

(defn- pct [n total]
  (if (and total (pos? total)) (Math/round (* 100.0 (/ (double n) total))) 0))

(defn- coverage-row [[field rows]]
  (let [total (reduce + (map :n rows))
        found (reduce + (map :n (remove (comp nil? :source) rows)))]
    [:tr
     [:th (name field)]
     [:td (str (pct found total) "%")]
     [:td
      [:span.sources
       (for [{:keys [source n]} rows]
         [:span.source (str (or source "none") " " (pct n total) "%")])]]]))

(defn- overview-stats [{:keys [total done failed low-confidence
                               avg-body-confidence avg-entity-confidence]}
                       {:keys [failed-urls recovered]}]
  [:dl.stats
   [:div [:dt "Extractions"] [:dd total]]
   [:div [:dt "Succeeded"] [:dd (str done " (" (pct done total) "%)")]]
   [:div [:dt "Failed"] [:dd failed]]
   [:div [:dt "Low confidence"] [:dd low-confidence]]
   [:div [:dt "Avg body conf."] [:dd (str avg-body-confidence)]]
   [:div [:dt "Avg entity conf."] [:dd (str avg-entity-confidence)]]
   [:div [:dt "Failed → recovered"] [:dd (str recovered " / " failed-urls)]]])

(defn render [{:keys [overview coverage by-domain errors latency recovery recent-failures]}]
  (layout/page
   "Extraction eval"
   [:main
    [:nav.index-nav.muted [:a {:href "/"} "Back to your reading list"]]
    [:h1 "Extraction eval"]

    [:section
     [:h2 "Overview"]
     (if (zero? (or (:total overview) 0))
       [:p.muted "No extractions recorded yet."]
       (overview-stats overview recovery))]

    [:section
     [:h2 "Field coverage by source"]
     [:p.muted "How often each field was found, and where it came from. Low coverage (or a high "
      [:code "none"] " share) is the cue that an LLM tier might earn its place."]
     [:table.eval
      [:thead [:tr [:th "Field"] [:th "Found"] [:th "By source"]]]
      (into [:tbody] (map coverage-row coverage))]]

    [:section
     [:h2 "By source domain"]
     [:table.eval
      [:thead [:tr [:th "Domain"] [:th "Count"] [:th "Done"] [:th "Avg conf."]]]
      (into [:tbody]
            (for [{:keys [domain n done avg-confidence]} by-domain]
              [:tr [:th domain] [:td n] [:td done] [:td (str avg-confidence)]]))]]

    (when (seq errors)
      [:section
       [:h2 "Failures by class"]
       [:table.eval
        [:thead [:tr [:th "Error"] [:th "Count"]]]
        (into [:tbody]
              (for [{:keys [error-class n]} errors]
                [:tr [:th (or error-class "unknown")] [:td n]]))]])

    (let [{:keys [p50-fetch-ms p95-fetch-ms p50-extract-ms p95-extract-ms]} latency]
      [:section
       [:h2 "Latency (ms)"]
       [:p.muted (str "fetch p50 " (or p50-fetch-ms "–") " / p95 " (or p95-fetch-ms "–")
                      " · extract p50 " (or p50-extract-ms "–") " / p95 " (or p95-extract-ms "–"))]])

    (when (seq recent-failures)
      [:section
       [:h2 "Recent failures"]
       (into [:ul.muted]
             (for [{:keys [url error-class]} recent-failures]
               [:li [:span error-class] " — " [:span.import-url url]]))])]))
