(ns reader.ui.pages.authors
  "Author index and show pages."
  (:require [reader.ui.layout :as layout]))

(defn index [authors]
  (layout/app-page
   "Authors" :authors
   (list
    [:div.page-head [:h1 "Authors"]]
    (if (seq authors)
      (into [:ul.entities]
            (map (fn [a]
                   [:li [:a {:href (str "/authors/" (:authors/slug a))}
                         (:authors/name a)]])
                 authors))
      [:p.muted "No authors yet."]))))

(defn show
  "An author and the outlets they write for. A placeholder for now — this is
   where everything by the author will eventually live."
  [author affiliations]
  (layout/app-page
   (:authors/name author) :authors
   (list
    [:nav.backnav [:a {:href "/authors"} "All authors"]]
    [:h1 (:authors/name author)]
    (when-let [bio (:authors/bio author)]
      [:p bio])
    (when (seq affiliations)
      (into [:p.muted "Writes for: "]
            (interpose ", " (map :name affiliations)))))))
