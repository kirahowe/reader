(ns reader.ui.pages.affiliations
  "The sources index — publications, newsletters, journals, and the like."
  (:require [reader.ui.components :as c]
            [reader.ui.layout :as layout]))

(defn index [affiliations]
  (layout/app-page
   "Sources" :sources
   (list
    (c/page-head "Sources")
    (if (seq affiliations)
      (into [:ul.entities]
            (map (fn [a]
                   [:li (:affiliations/name a)
                    " " [:span.entity-type (:affiliations/type a)]])
                 affiliations))
      [:p.muted "No sources yet."]))))
