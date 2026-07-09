(ns reader.ui.components
  "The design system's building blocks: small, composable hiccup primitives that
   every page is assembled from, so new screens stay consistent by construction.

   Conventions
   - Each primitive returns plain hiccup and owns a single semantic class
     (`.card`, `.chip`, `.field`, …). Styling lives in main.css under the same
     name — no inline styles, no utility-class soup.
   - Variants are modifier classes on the block: `chip--read`, `btn--primary`.
   - Icons are 24×24, stroke `currentColor`, and inherit their size from CSS.

   See docs/design-system.md for the full catalogue."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Icons — inline SVG so they inherit `color` and font-size from their context.
;; ---------------------------------------------------------------------------

(def icon-book
  "The wordmark glyph: an open book."
  [:svg {:viewBox "0 0 24 24" :fill "none" :aria-hidden "true"}
   [:path {:d "M4 5.5C4 4.7 4.7 4 5.5 4H11v15H5.5A1.5 1.5 0 0 0 4 20.5V5.5Z"
           :stroke "currentColor" :stroke-width "1.7" :stroke-linejoin "round"}]
   [:path {:d "M20 5.5C20 4.7 19.3 4 18.5 4H13v15h5.5a1.5 1.5 0 0 1 1.5 1.5V5.5Z"
           :stroke "currentColor" :stroke-width "1.7" :stroke-linejoin "round"}]])

(def icon-archive
  "The archive action: a storage box. Archiving is reversible (re-adding
   restores the item), so the glyph is a box, not a bin."
  [:svg {:viewBox "0 0 24 24" :fill "none" :aria-hidden "true"}
   [:rect {:x "3" :y "4" :width "18" :height "4" :rx "1"
           :stroke "currentColor" :stroke-width "1.7" :stroke-linejoin "round"}]
   [:path {:d "M5 8v10.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V8"
           :stroke "currentColor" :stroke-width "1.7" :stroke-linecap "round"}]
   [:path {:d "M10 12h4" :stroke "currentColor" :stroke-width "1.7" :stroke-linecap "round"}]])

(def icon-chevron-left
  [:svg {:viewBox "0 0 24 24" :fill "none" :aria-hidden "true"}
   [:path {:d "M15 18l-6-6 6-6" :stroke "currentColor" :stroke-width "2"
           :stroke-linecap "round" :stroke-linejoin "round"}]])

;; ---------------------------------------------------------------------------
;; Class helper — merge a base class with optional modifier/extra classes.
;; ---------------------------------------------------------------------------

(defn- classes
  "Join non-nil class fragments into a single space-separated string."
  [& parts]
  (->> parts (remove nil?) (str/join " ") not-empty))

;; ---------------------------------------------------------------------------
;; Links & text
;; ---------------------------------------------------------------------------

(defn author-link [{:keys [name slug]}]
  [:a {:href (str "/authors/" slug)} name])

(defn byline
  "A comma-separated span of author links, or nil when there are no authors.
   `authors` is a seq of {:name :slug} maps."
  [authors]
  (when (seq authors)
    (into [:span] (interpose ", " (map author-link authors)))))

(defn kicker
  "The small uppercase eyebrow above a headline (e.g. a source name)."
  [text]
  [:span.kicker text])

(defn readable-list
  "A <ul> of readables, each title linking out to its external original — or shown
   as plain text when it has none (a paper with no DOI/arXiv id; the in-app reader
   view is owner-scoped per queue item, so it isn't linked from a browse page).
   `items` are normalized readables ({:title :url})."
  [items]
  (into [:ul.entities]
        (map (fn [{:keys [title url]}]
               [:li (if url [:a {:href url} title] title)]))
        items))

;; ---------------------------------------------------------------------------
;; Buttons — a bare <button> is the quiet secondary control; `:variant` adds a
;; modifier. `:primary` fills with the accent, `:icon` is a quiet glyph button,
;; `:link` reads as inline text. `opts` is merged onto the element.
;; ---------------------------------------------------------------------------

(def ^:private button-variant
  {:primary "btn--primary" :icon "btn--icon" :link "btn--link"})

(defn button
  "A <button>. Pass `:variant` in `opts` for a non-default style; any other keys
   (`:type`, `:aria-label`, data-* …) pass straight through."
  [opts & content]
  (let [attrs (-> (dissoc opts :variant)
                  (update :class #(classes % (button-variant (:variant opts)))))]
    (into [:button attrs] content)))

;; ---------------------------------------------------------------------------
;; Chips — small status pills. nil variant is the accent chip; pass a keyword
;; (`:read`, `:reading`) for a modifier.
;; ---------------------------------------------------------------------------

(defn chip
  ([text] (chip nil text))
  ([variant text]
   [:span {:class (classes "chip" (when variant (str "chip--" (name variant))))} text]))

;; ---------------------------------------------------------------------------
;; Containers
;; ---------------------------------------------------------------------------

(defn card
  "A raised surface (settings blocks, callouts)."
  [& content]
  (into [:section.card] content))

(defn page-head
  "The standard heading row: a title (optionally with a subtitle) on the left and
   an optional action (a link or button) on the right."
  ([title] (page-head title nil nil))
  ([title subtitle] (page-head title subtitle nil))
  ([title subtitle action]
   [:div.page-head
    [:div
     [:h1 title]
     (when subtitle [:p.page-sub.muted subtitle])]
    action]))

(defn back-link
  "The back affordance shown at the top of sub-pages."
  ([href] (back-link href "Queue"))
  ([href label]
   [:nav.backnav
    [:a {:href href} [:span {:aria-hidden "true"} icon-chevron-left] label]]))

;; ---------------------------------------------------------------------------
;; Form fields — a labelled control with an optional error, so every form reads
;; the same. `error` is the seq of messages Malli yields (we show the first).
;; ---------------------------------------------------------------------------

(defn- field [label control error]
  [:label.field
   [:span.label-text label]
   control
   (when error [:span.error (first error)])])

(defn text-field
  ([label input-name value error] (text-field label input-name value error "text"))
  ([label input-name value error input-type]
   (field label [:input {:type input-type :name input-name :value value}] error)))
