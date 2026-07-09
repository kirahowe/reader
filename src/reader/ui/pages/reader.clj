(ns reader.ui.pages.reader
  "The reader view: one queued readable. Renders the uniform content map from
   `reader.extract` (so it's blind to readable type) plus the read controls,
   which are driven by the queue item's state."
  (:require [reader.ui.components :as components]
            [reader.ui.layout :as layout]))

(defn- format-date
  "A readable date (yyyy-MM-dd, UTC) for the temporal types a timestamptz column
   yields. Falls back to the ISO-string prefix for any other java.time value, so
   it never throws."
  [date]
  (let [instant (cond
                  (instance? java.time.Instant date)   date
                  (instance? java.sql.Timestamp date)  (.toInstant ^java.sql.Timestamp date))]
    (if instant
      (.format (.atZone ^java.time.Instant instant java.time.ZoneOffset/UTC)
               java.time.format.DateTimeFormatter/ISO_LOCAL_DATE)
      (let [s (str date)]
        (if (>= (count s) 10) (subs s 0 10) s)))))

(defn- meta-line
  "Byline and date under the title, each shown only when present. The source is
   promoted to the kicker above the title, so it is not repeated here."
  [{:keys [authors date]}]
  (let [byline-frag (when-let [b (components/byline authors)] [:span.meta-item b])
        date-frag   (when date [:span.meta-item (format-date date)])
        parts       (remove nil? [byline-frag date-frag])]
    (when (seq parts)
      (into [:div.reader-meta] parts))))

(defn- http-url?
  "True only for http(s) URLs. This is the render boundary where a DB-sourced href
   first becomes clickable; rejecting other schemes (e.g. `javascript:`) here
   stops a stored value from executing on click regardless of how it was written."
  [href]
  (boolean (and (string? href) (re-find #"(?i)^https?://" href))))

(defn- links-line [links]
  (when-let [safe (seq (filter (comp http-url? :href) links))]
    (into [:p.reader-links]
          (interpose " · "
                     (map (fn [{:keys [label href]}]
                            ;; new tab + noopener/noreferrer so external pages can't reach window.opener or leak the referrer
                            [:a.external {:href href :target "_blank" :rel "noopener noreferrer"} label])
                          safe)))))

(defn- unsubscribe-link
  "The newsletter's own unsubscribe affordance, from its List-Unsubscribe header.
   Only http(s) (new tab, hardened) and mailto are rendered; any other scheme is
   dropped, since this href is a stored, sender-controlled value."
  [{:keys [unsubscribe-url]}]
  (when (string? unsubscribe-url)
    (cond
      (re-find #"(?i)^https?://" unsubscribe-url)
      [:a.external {:href unsubscribe-url :target "_blank" :rel "noopener noreferrer"} "Unsubscribe"]
      (re-find #"(?i)^mailto:" unsubscribe-url)
      [:a {:href unsubscribe-url} "Unsubscribe"])))

(defn- toggle-form
  "The read/unread toggle as a form. The Datastar intercept lets the server
   patch #reader-controls in place over SSE (no reload); the plain
   method/action keeps it working without JavaScript."
  [id verb label]
  (let [action (str "/queue/" id "/" verb)]
    [:form {:method "post" :action action
            :data-on-submit__prevent (str "@post('" action "')")}
     (components/button {:type "submit" :variant :primary} label)]))

(defn controls
  "Read/unread toggle (by state) plus archive, as the patchable #reader-controls
   fragment. The toggle is the primary action; archive stays a quiet secondary —
   and a plain form, since archiving leaves this page for the queue anyway.
   Hidden for an archived item, which is only reachable by a stale or hand-typed
   URL — the wrapper div still renders so an SSE patch always has its target."
  [{:queue-items/keys [id state]}]
  [:div#reader-controls.reader-actions
   (when (not= "archived" state)
     (list
      (if (= "read" state)
        (toggle-form id "unread" "Mark unread")
        (toggle-form id "read"   "Mark read"))
      [:form {:method "post" :action (str "/queue/" id "/archive")}
       (components/button {:type "submit"} "Archive")]))])

(defn tags-editor
  "This item's effective tags — each a chip with a remove control — plus an
   add-tag field, as the patchable #reader-tags fragment. Owner-scoped writes
   post to /queue/:id/tags(/...); with Datastar the server patches this section
   back over SSE and clears the $tag signal the input is bound to."
  [queue-item-id tags]
  [:section#reader-tags.reader-tags {:aria-label "Tags"}
   (into [:ul.reader-tag-list]
         (concat
          (map (fn [{:keys [id label]}]
                 (let [action (str "/queue/" queue-item-id "/tags/" id "/remove")]
                   [:li.reader-tag
                    [:span.chip.chip--tag label]
                    [:form {:method "post" :action action
                            :data-on-submit__prevent (str "@post('" action "')")}
                     [:button.tag-remove {:type "submit" :aria-label (str "Remove tag: " label)} "×"]]]))
               tags)
          [[:li.reader-tag-add
            (let [action (str "/queue/" queue-item-id "/tags")]
              [:form {:method "post" :action action
                      :data-on-submit__prevent (str "@post('" action "', {contentType: 'form'})")}
               [:input {:type "text" :name "label" :data-bind "tag" :placeholder "Add a tag…"
                        :required true :autocomplete "off" :maxlength "60"}]
               (components/button {:type "submit"} "Add")])]]))])

(defn show
  "`queue-item` is the raw queue_items row (for state/controls); `content` is the
   uniform map from `reader.extract`; `tags` is the item's effective tag set."
  [queue-item content tags]
  (layout/app-page
   (:title content) nil
   (list
    (components/back-link "/")
    [:article.reader
     [:div.reader-head
      (when-let [source (:source content)]
        (components/kicker (:name source)))
      [:h1 (:title content)]
      (meta-line content)]
     (:body content)
     (links-line (:links content))
     (controls queue-item)
     (tags-editor (:queue-items/id queue-item) tags)
     (when-let [unsub (unsubscribe-link content)]
       [:p.reader-unsubscribe unsub])])))
