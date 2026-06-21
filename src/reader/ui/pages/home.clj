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

(defn- tag-chip
  "A tag rendered as a chip-link to its filtered list view; the active tag (the
   one currently filtering) is marked so it reads as selected. `nav?` adds
   aria-current on the active chip — set it for the filter bar (the one nav where
   a tag is genuinely \"current\"), not the per-row strips where it would repeat."
  ([active tag] (tag-chip active tag false))
  ([active {:keys [slug label]} nav?]
   (let [current? (= slug active)]
     [:a (cond-> {:href  (str "/?tag=" slug)
                  :class (str "chip chip--tag" (when current? " chip--active"))}
           (and nav? current?) (assoc :aria-current "true"))
      label])))

(defn item [{:keys [queue-item-id title tags active-tag] :as readable}]
  [:li.readable {:id (str "q-" queue-item-id)}
   [:div.readable-text
    [:h2.readable-title [:a {:href (str "/queue/" queue-item-id)} title]]
    (meta-line readable)
    (when (seq tags)
      (into [:div.readable-tags] (map #(tag-chip active-tag %) tags)))]
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
  "A queue row for a readable whose import permanently failed. No polling — this is
   terminal. Articles offer the manual add form as a fallback; papers and
   newsletters have no manual entry path, so `type` gates that link off rather than
   pointing them at the article-only form."
  [queue-item-id label type]
  [:li.readable.failed {:id (str "q-" queue-item-id)}
   [:div.readable-text
    [:h2.readable-title "Couldn’t import"]
    (into [:div.readable-meta [:span.meta-item [:span.import-url label]]]
          (when (= type :article)
            [[:span.meta-item [:a {:href "/articles/new"} "add manually"]]]))]
   [:form.readable-actions {:method "post" :action (str "/queue/" queue-item-id "/archive")}
    (c/button {:type "submit" :variant :icon :aria-label "Archive"} c/icon-trash)]])

(defn unavailable-row
  "A terminal row for a paper whose source metadata isn't available yet — a brand
   new DOI OpenAlex hasn't indexed. No polling, and worded honestly: re-adding now
   won't help (it's days out), but checking back later will."
  [queue-item-id title]
  [:li.readable.unavailable {:id (str "q-" queue-item-id)}
   [:div.readable-text
    [:h2.readable-title title]
    [:div.readable-meta
     [:span.meta-item
      "Not indexed yet — new papers can take a few days to appear. Check back later."]]]
   [:form.readable-actions {:method "post" :action (str "/queue/" queue-item-id "/archive")}
    (c/button {:type "submit" :variant :icon :aria-label "Archive"} c/icon-trash)]])

(defn- subtitle [readables]
  (let [n (count readables)]
    (str n (if (= 1 n) " item" " items") " to work through")))

(defn- filter-bar
  "The tag filter row above the list: an \"All\" affordance plus a chip per tag in
   the user's vocabulary. Hidden until there are any tags to filter by."
  [all-tags active]
  (when (seq all-tags)
    (into [:nav.tag-filter {:aria-label "Filter reading list by tag"}
           [:a (cond-> {:href "/" :class (str "chip chip--tag" (when (nil? active) " chip--active"))}
                 (nil? active) (assoc :aria-current "true"))
            "All"]]
          (map #(tag-chip active % :nav) all-tags))))

(defn render
  "The reading list. `readables` are the (already tag-filtered) queue items,
   `active` the slug currently filtering (or nil), `all-tags` the full vocabulary
   for the filter bar."
  [readables active all-tags]
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
    (filter-bar all-tags active)
    ;; Always render the list (even empty) so the HTMX afterbegin target exists.
    [:ul.readables {:id "readables-list"}
     (map #(item (assoc % :active-tag active)) readables)]
    (when-not (seq readables)
      [:p.muted (if active
                  "No items with this tag yet."
                  "Nothing here yet — paste a URL above to add your first article.")]))))
