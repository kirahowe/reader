(ns reader.ui.pages.affiliations
  "The sources index — publications, newsletters, journals, and the like."
  (:require [reader.ui.layout :as layout]))

(defn index [affiliations]
  (layout/page
   "Sources"
   [:main
    [:nav.index-nav.muted [:a {:href "/"} "Back to your reading list"]]
    [:h1 "Sources"]
    (if (seq affiliations)
      (into [:ul.entities]
            (map (fn [a]
                   [:li (:affiliations/name a)
                    " " [:span.muted (:affiliations/type a)]])
                 affiliations))
      [:p.muted "No sources yet."])]))
