(ns reader.ui.pages.authors
  "Author index and show pages."
  (:require [reader.ui.components :as c]
            [reader.ui.layout :as layout]))

(defn index [authors]
  (layout/app-page
   "Authors" :authors
   (list
    (c/page-head "Authors")
    (if (seq authors)
      (into [:ul.entities]
            (map (fn [a]
                   [:li [:a {:href (str "/authors/" (:authors/slug a))}
                         (:authors/name a)]])
                 authors))
      [:p.muted "No authors yet."]))))

(defn- affiliated-section
  "The institutions this author is affiliated with (the academic sense, from
   papers). Rendered as plain text — institutions aren't browsable sources. Hidden
   entirely when there are none, so non-academic authors don't show an empty box."
  [institutions]
  (when (seq institutions)
    [:section.entity-section
     [:h2 "Affiliated with"]
     (into [:ul.entities]
           (map (fn [{:keys [name]}] [:li name]) institutions))]))

(defn- published-in-section
  "The sources this author has published in — derived from their works, each
   linking to its source page. Hidden when empty."
  [sources]
  (when (seq sources)
    [:section.entity-section
     [:h2 "Published in"]
     (into [:ul.entities]
           (map (fn [{:keys [name slug type]}]
                  [:li [:a {:href (str "/affiliations/" slug)} name]
                   " " [:span.entity-type type]])
                sources))]))

(defn- works-section
  "The articles and papers credited to this author."
  [works]
  [:section.entity-section
   [:h2 "Works"]
   (if (seq works)
     (c/readable-list works)
     [:p.muted "Nothing attributed yet."])])

(defn show
  "An author page: the institutions they're affiliated with, the sources they've
   published in (derived), and their works — the surface for eyeballing
   entity-extraction quality."
  [author institutions sources works]
  (layout/app-page
   (:authors/name author) :authors
   (list
    (c/back-link "/authors" "All authors")
    [:h1 (:authors/name author)]
    (when-let [bio (:authors/bio author)]
      [:p.entity-lead bio])
    (affiliated-section institutions)
    (published-in-section sources)
    (works-section works))))
