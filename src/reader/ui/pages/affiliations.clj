(ns reader.ui.pages.affiliations
  "The sources index and show pages — publications, newsletters, journals, and the
   like, plus the articles and authors that hang off each."
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
                   [:li [:a {:href (str "/affiliations/" (:affiliations/slug a))}
                         (:affiliations/name a)]
                    " " [:span.entity-type (:affiliations/type a)]])
                 affiliations))
      [:p.muted "No sources yet."]))))

(defn- header-meta
  "The line under the title: the source type and, when known, its homepage."
  [{:affiliations/keys [type url]}]
  (let [parts (remove nil? [(when type [:span.entity-type type])
                            (when url [:a {:href url} url])])]
    (when (seq parts)
      (into [:p.entity-meta] (interpose " · " parts)))))

(defn- works-section
  "The articles and papers published by this source, each linking out to the
   original; the in-app reader view is owner-scoped per queue item."
  [works]
  [:section.entity-section
   [:h2 "Works"]
   (if (seq works)
     (c/readable-list works)
     [:p.muted "Nothing from this source yet."])])

(defn- authors-section
  "The authors who've published in this source — derived from its works, each
   linking to their own page."
  [authors]
  [:section.entity-section
   [:h2 "Authors"]
   (if (seq authors)
     (into [:ul.entities]
           (map (fn [{:keys [name slug]}]
                  [:li [:a {:href (str "/authors/" slug)} name]])
                authors))
     [:p.muted "No authors linked yet."])])

(defn show
  "A source page: the works it published and the authors who've published in it
   (both derived from its readables) — the reverse view of the author page, for
   eyeballing entity-extraction quality."
  [affiliation works authors]
  (layout/app-page
   (:affiliations/name affiliation) :sources
   (list
    (c/back-link "/affiliations" "All sources")
    [:h1 (:affiliations/name affiliation)]
    (header-meta affiliation)
    (when-let [d (:affiliations/description affiliation)]
      [:p.entity-lead d])
    (works-section works)
    (authors-section authors))))
