(ns reader.ui.pages.home
  "The home page: the reading queue — every readable, with its source and
   byline. The author's own affiliation is deliberately not shown here; it
   lives on the author page.

   Reactivity is Datastar, server-authoritative: the add form @posts and the
   server prepends an importing row over SSE; that row polls its own /row
   endpoint until the import settles; archiving a row removes it in place.
   Every form keeps a plain method/action, so the page still works without
   JavaScript."
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

(defn- archive-form
  "The row's archive control: a real form (works without JS), intercepted by
   Datastar so the row disappears in place."
  [queue-item-id]
  (let [action (str "/queue/" queue-item-id "/archive")]
    [:form.readable-actions {:method "post" :action action
                             :data-on-submit__prevent (str "@post('" action "')")}
     (c/button {:type "submit" :variant :icon :aria-label "Archive"} c/icon-archive)]))

(defn item [{:keys [queue-item-id title tags active-tag] :as readable}]
  [:li.readable {:id (str "q-" queue-item-id)}
   [:div.readable-text
    [:h2.readable-title [:a {:href (str "/queue/" queue-item-id)} title]]
    (meta-line readable)
    (when (seq tags)
      (into [:div.readable-tags] (map #(tag-chip active-tag %) tags)))]
   (archive-form queue-item-id)])

(defn importing-row
  "A placeholder queue row for an article still being fetched/extracted. It
   polls its own /queue/:id/row endpoint; the server patches the settled row in
   (which carries no interval, so the poll stops on its own)."
  [queue-item-id url]
  [:li.readable.importing {:id (str "q-" queue-item-id)
                           :data-on-interval__duration.2s
                           (str "@get('/queue/" queue-item-id "/row')")}
   [:div.readable-text
    [:h2.readable-title [:span.spinner {:aria-hidden "true"}] " Importing…"]
    [:div.readable-meta [:span.meta-item [:span.import-url url]]]]])

(defn failed-row
  "A queue row for a readable whose import permanently failed. No polling — this is
   terminal; the only action is to archive it."
  [queue-item-id label]
  [:li.readable.failed {:id (str "q-" queue-item-id)}
   [:div.readable-text
    [:h2.readable-title "Couldn’t import"]
    [:div.readable-meta [:span.meta-item [:span.import-url label]]]]
   (archive-form queue-item-id)])

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
   (archive-form queue-item-id)])

(defn- subtitle [readables]
  (let [n (count readables)]
    (str n (if (= 1 n) " item" " items"))))

(defn- add-form
  "The add-by-URL bar. The input is bound to the $url signal so the server can
   clear it after a successful add; $adding (set by Datastar for the duration of
   the request) drives the pending state."
  []
  [:form.add-url {:method "post" :action "/readables"
                  :data-indicator "adding"
                  :data-class "{'is-adding': $adding}"
                  :data-on-submit__prevent "@post('/readables', {contentType: 'form'})"}
   [:input {:type "url" :name "url" :data-bind "url"
            :placeholder "Paste a URL to save it"
            :aria-label "URL of an article, paper, or newsletter to add"
            :required true :autocomplete "off"}]
   (c/button {:type "submit" :variant :primary :data-attr-disabled "$adding"}
             [:span.add-label "Add"]
             [:span.add-busy {:aria-hidden "true"} [:span.spinner] "Adding"])])

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
  "The reading queue. `readables` are the (already tag-filtered) queue items,
   `active` the slug currently filtering (or nil), `all-tags` the full vocabulary
   for the filter bar."
  [readables active all-tags]
  (layout/app-page
   "Reader" :queue
   (list
    (c/page-head "Queue"
                 (when (seq readables) (subtitle readables)))
    (add-form)
    (filter-bar all-tags active)
    ;; The list always renders (even empty) so SSE prepends have a target; the
    ;; empty-state message is CSS on the empty <ul>, so it appears and
    ;; disappears with the rows without any bookkeeping.
    (into [:ul.readables {:id "readables-list"
                          :class (when active "is-filtered")}]
          (map #(item (assoc % :active-tag active)) readables)))))
