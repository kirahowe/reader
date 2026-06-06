(ns reader.ui.pages.authors
  "Author index and show pages."
  (:require [reader.ui.layout :as layout]))

(defn index [authors]
  (layout/page
   "Authors"
   [:main
    [:nav.index-nav.muted [:a {:href "/"} "Back to your reading list"]]
    [:h1 "Authors"]
    (if (seq authors)
      (into [:ul.entities]
            (map (fn [a]
                   [:li [:a {:href (str "/authors/" (:authors/slug a))}
                         (:authors/name a)]])
                 authors))
      [:p.muted "No authors yet."])]))

(defn show
  "An author and the outlets they write for. A placeholder for now — this is
   where everything by the author will eventually live."
  [author affiliations]
  (layout/page
   (:authors/name author)
   [:main
    [:nav.index-nav.muted [:a {:href "/"} "Back to your reading list"]]
    [:h1 (:authors/name author)]
    (when-let [bio (:authors/bio author)]
      [:p bio])
    (when (seq affiliations)
      (into [:p.muted "Writes for: "]
            (interpose ", " (map :name affiliations))))]))
