(ns reader.ui.pages.home
  "The home page: the reading list — every readable, with its source and
   byline. The author's own affiliation is deliberately not shown here; it
   lives on the author page."
  (:require [reader.ui.layout :as layout]))

(defn- author-link [{:keys [name slug]}]
  [:a {:href (str "/authors/" slug)} name])

(defn- meta-line
  "The subtle line under a title: source, then byline, joined by a dot. Each
   part appears only when present, so a source-less or author-less readable
   still reads cleanly."
  [{:keys [source authors]}]
  (let [source-frag (when source [:span (:name source)])
        byline-frag (when (seq authors)
                      (into [:span] (interpose ", " (map author-link authors))))
        parts       (remove nil? [source-frag byline-frag])]
    (when (seq parts)
      (into [:div.readable-meta.muted] (interpose " · " parts)))))

(def ^:private trash-icon
  [:svg {:viewBox "0 0 24 24" :fill "none" :aria-hidden "true"}
   [:path {:d "M18 6L17.1991 18.0129C17.129 19.065 17.0939 19.5911 16.8667 19.99C16.6666 20.3412 16.3648 20.6235 16.0011 20.7998C15.588 21 15.0607 21 14.0062 21H9.99377C8.93927 21 8.41202 21 7.99889 20.7998C7.63517 20.6235 7.33339 20.3412 7.13332 19.99C6.90607 19.5911 6.871 19.065 6.80086 18.0129L6 6M4 6H20M16 6L15.7294 5.18807C15.4671 4.40125 15.3359 4.00784 15.0927 3.71698C14.8779 3.46013 14.6021 3.26132 14.2905 3.13878C13.9376 3 13.523 3 12.6936 3H11.3064C10.477 3 10.0624 3 9.70951 3.13878C9.39792 3.26132 9.12208 3.46013 8.90729 3.71698C8.66405 4.00784 8.53292 4.40125 8.27064 5.18807L8 6M14 10V17M10 10V17"
           :stroke "currentColor" :stroke-width "2"
           :stroke-linecap "round" :stroke-linejoin "round"}]])

(defn- item [{:keys [table id title] :as readable}]
  [:li.readable
   [:div.readable-text
    [:div.readable-title title]
    (meta-line readable)]
   [:form.readable-actions {:method "post"
                            :action (str "/readables/" (name table) "/" id "/delete")}
    [:button {:type "submit" :aria-label "Remove from reading list"} trash-icon]]])

(defn render [readables]
  (layout/page
   "Reader"
   [:main
    [:h1 "Your reading list"]
    [:nav.index-nav.muted
     [:a {:href "/articles/new"} "Add article"] " · "
     [:a {:href "/authors"} "All authors"] " · "
     [:a {:href "/affiliations"} "All sources"] " · "
     [:form.logout {:method "post" :action "/logout"}
      [:button {:type "submit"} "Sign out"]]]
    (if (seq readables)
      (into [:ul.readables] (map item readables))
      [:p.muted "Nothing here yet."])]))
