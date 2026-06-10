(ns reader.ui.pages.home
  "The home page: the reading list — every readable, with its source and
   byline. The author's own affiliation is deliberately not shown here; it
   lives on the author page."
  (:require [reader.ui.components :as components]
            [reader.ui.layout :as layout]))

(defn- meta-line
  "The subtle line under a title: queue state (when past unread), source, then
   byline, joined by a dot. Each part appears only when present, so a state-less
   or source-less readable still reads cleanly."
  [{:keys [state source authors]}]
  (let [state-frag  (when (and state (not= "unread" state)) [:span.queue-state state])
        source-frag (when source [:span (:name source)])
        byline-frag (components/byline authors)
        parts       (remove nil? [state-frag source-frag byline-frag])]
    (when (seq parts)
      (into [:div.readable-meta.muted] (interpose " · " parts)))))

(def ^:private trash-icon
  [:svg {:viewBox "0 0 24 24" :fill "none" :aria-hidden "true"}
   [:path {:d "M18 6L17.1991 18.0129C17.129 19.065 17.0939 19.5911 16.8667 19.99C16.6666 20.3412 16.3648 20.6235 16.0011 20.7998C15.588 21 15.0607 21 14.0062 21H9.99377C8.93927 21 8.41202 21 7.99889 20.7998C7.63517 20.6235 7.33339 20.3412 7.13332 19.99C6.90607 19.5911 6.871 19.065 6.80086 18.0129L6 6M4 6H20M16 6L15.7294 5.18807C15.4671 4.40125 15.3359 4.00784 15.0927 3.71698C14.8779 3.46013 14.6021 3.26132 14.2905 3.13878C13.9376 3 13.523 3 12.6936 3H11.3064C10.477 3 10.0624 3 9.70951 3.13878C9.39792 3.26132 9.12208 3.46013 8.90729 3.71698C8.66405 4.00784 8.53292 4.40125 8.27064 5.18807L8 6M14 10V17M10 10V17"
           :stroke "currentColor" :stroke-width "2"
           :stroke-linecap "round" :stroke-linejoin "round"}]])

(defn item [{:keys [queue-item-id title] :as readable}]
  [:li.readable {:id (str "q-" queue-item-id)}
   [:div.readable-text
    [:div.readable-title [:a {:href (str "/queue/" queue-item-id)} title]]
    (meta-line readable)]
   [:form.readable-actions {:method "post"
                            :action (str "/queue/" queue-item-id "/archive")}
    [:button {:type "submit" :aria-label "Archive"} trash-icon]]])

(defn importing-row
  "A placeholder queue row for an article still being fetched/extracted. It
   polls its own /queue/:id/row endpoint and replaces itself (outerHTML) once
   the status changes to done or failed."
  [queue-item-id url]
  [:li.readable.importing {:id         (str "q-" queue-item-id)
                           :hx-get     (str "/queue/" queue-item-id "/row")
                           :hx-trigger "load delay:1.5s, every 2s"
                           :hx-swap    "outerHTML"}
   [:div.readable-text
    [:div.readable-title [:span.spinner {:aria-hidden "true"}] " Importing…"]
    [:div.readable-meta.muted [:span.import-url url]]]])

(defn failed-row
  "A queue row for an article whose import permanently failed; offers the manual
   add form as a fallback. No polling — this is terminal."
  [queue-item-id url]
  [:li.readable.failed {:id (str "q-" queue-item-id)}
   [:div.readable-text
    [:div.readable-title "Couldn’t import"]
    [:div.readable-meta.muted [:span.import-url url] " · " [:a {:href "/articles/new"} "add manually"]]]
   [:form.readable-actions {:method "post" :action (str "/queue/" queue-item-id "/archive")}
    [:button {:type "submit" :aria-label "Archive"} trash-icon]]])

(defn render [readables]
  (layout/page
   "Reader"
   [:main
    [:h1 "Your reading list"]
    [:nav.index-nav.muted
     [:a {:href "/articles/new"} "Add manually"] " · "
     [:a {:href "/authors"} "All authors"] " · "
     [:a {:href "/affiliations"} "All sources"] " · "
     [:a {:href "/settings"} "Settings"] " · "
     [:form.logout {:method "post" :action "/logout"}
      [:button {:type "submit"} "Sign out"]]]
    [:form.add-url {:method  "post" :action "/readables"
                    :hx-post "/readables" :hx-target "#readables-list" :hx-swap "afterbegin"}
     [:input {:type "url" :name "url" :placeholder "Paste an article URL…"
              :required true :autocomplete "off"}]
     [:button {:type "submit"} "Add"]]
    ;; Always render the list (even empty) so the HTMX afterbegin target exists.
    [:ul.readables {:id "readables-list"} (map item readables)]
    (when-not (seq readables)
      [:p.muted "Nothing here yet — paste a URL above to add your first article."])]))
