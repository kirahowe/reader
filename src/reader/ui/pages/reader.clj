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
  "Source, byline, and date under the title, each shown only when present."
  [{:keys [source authors date]}]
  (let [source-frag (when source [:span (:name source)])
        byline-frag (components/byline authors)
        date-frag   (when date [:span (format-date date)])
        parts       (remove nil? [source-frag byline-frag date-frag])]
    (when (seq parts)
      (into [:div.reader-meta.muted] (interpose " · " parts)))))

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

(defn- action-form [id verb label]
  [:form {:method "post" :action (str "/queue/" id "/" verb)}
   [:button {:type "submit"} label]])

(defn- controls
  "Read/unread toggle (by state) plus archive. Hidden for an archived item, which
   is only reachable by a stale or hand-typed URL."
  [{:queue-items/keys [id state]}]
  (when (not= "archived" state)
    [:div.reader-actions
     (if (= "read" state)
       (action-form id "unread" "Mark as unread")
       (action-form id "read"   "Mark as read"))
     (action-form id "archive" "Archive")]))

(defn show
  "`queue-item` is the raw queue_items row (for state/controls); `content` is the
   uniform map from `reader.extract`."
  [queue-item content]
  (layout/page
   (:title content)
   [:main
    [:nav.index-nav.muted [:a {:href "/"} "Back to your reading list"]]
    [:article.reader
     [:h1 (:title content)]
     (meta-line content)
     (:body content)
     (links-line (:links content))
     (controls queue-item)
     (when-let [unsub (unsubscribe-link content)]
       [:p.reader-unsubscribe unsub])]]))
