(ns reader.ui.pages.home
  "The home page: the reading list — every readable, with its source and
   byline. The author's own affiliation is deliberately not shown here; it
   lives on the author page."
  (:require [reader.ui.components :as c]
            [reader.ui.layout :as layout]))

(defn- meta-line
  "The subtle line under a title: an optional queue-state chip, then source and
   byline as separated meta items (CSS draws the separators). Each part appears
   only when present, so a state-less or source-less readable still reads cleanly."
  [{:keys [state source authors]}]
  (let [state-frag  (when (and state (not= "unread" state)) (c/chip (keyword state) state))
        source-frag (when source [:span.meta-item [:span.meta-source (:name source)]])
        byline-frag (when-let [b (c/byline authors)] [:span.meta-item b])
        parts       (remove nil? [state-frag source-frag byline-frag])]
    (when (seq parts)
      (into [:div.readable-meta] parts))))

(defn item [{:keys [queue-item-id title] :as readable}]
  [:li.readable {:id (str "q-" queue-item-id)}
   [:div.readable-text
    [:h2.readable-title [:a {:href (str "/queue/" queue-item-id)} title]]
    (meta-line readable)]
   [:form.readable-actions {:method "post"
                            :action (str "/queue/" queue-item-id "/archive")}
    (c/button {:type "submit" :variant :icon :aria-label "Archive"} c/icon-trash)]])

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
    [:h2.readable-title [:span.spinner {:aria-hidden "true"}] " Importing…"]
    [:div.readable-meta [:span.meta-item [:span.import-url url]]]]])

(defn failed-row
  "A queue row for an article whose import permanently failed; offers the manual
   add form as a fallback. No polling — this is terminal."
  [queue-item-id url]
  [:li.readable.failed {:id (str "q-" queue-item-id)}
   [:div.readable-text
    [:h2.readable-title "Couldn’t import"]
    [:div.readable-meta
     [:span.meta-item [:span.import-url url]]
     [:span.meta-item [:a {:href "/articles/new"} "add manually"]]]]
   [:form.readable-actions {:method "post" :action (str "/queue/" queue-item-id "/archive")}
    (c/button {:type "submit" :variant :icon :aria-label "Archive"} c/icon-trash)]])

(defn- subtitle [readables]
  (let [n (count readables)]
    (str n (if (= 1 n) " item" " items") " to work through")))

(defn render [readables]
  (layout/app-page
   "Reader" :queue
   (list
    (c/page-head "Your reading list"
                 (when (seq readables) (subtitle readables))
                 (c/link-button "/articles/new" "+ Add manually"))
    [:form.add-url {:method  "post" :action "/readables"
                    :hx-post "/readables" :hx-target "#readables-list" :hx-swap "afterbegin"}
     [:span.add-url-icon {:aria-hidden "true"} "+"]
     [:input {:type "url" :name "url" :placeholder "Paste an article, paper, or newsletter URL…"
              :required true :autocomplete "off"}]
     (c/button {:type "submit" :variant :primary} "Add to queue")]
    ;; Always render the list (even empty) so the HTMX afterbegin target exists.
    [:ul.readables {:id "readables-list"} (map item readables)]
    (when-not (seq readables)
      [:p.muted "Nothing here yet — paste a URL above to add your first article."]))))
