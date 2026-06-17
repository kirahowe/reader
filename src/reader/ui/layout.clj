(ns reader.ui.layout
  (:require [hiccup2.core :as h]))

(defn page
  "Wraps `body` in a complete HTML document with the standard head and
   stylesheets. `body` is a hiccup form (or seq of forms) that becomes the
   contents of <body>."
  [title body]
  (str "<!DOCTYPE html>\n"
       (h/html
        {:mode :html}
        [:html {:lang "en"}
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:title title]
          [:link {:rel "stylesheet" :href "/static/css/tokens.css"}]
          [:link {:rel "stylesheet" :href "/static/css/main.css"}]
          [:script {:src "/static/js/htmx.min.js" :defer true}]]
         [:body body]])))

(def book-icon
  "The wordmark glyph: an open book."
  [:svg {:viewBox "0 0 24 24" :fill "none" :aria-hidden "true"}
   [:path {:d "M4 5.5C4 4.7 4.7 4 5.5 4H11v15H5.5A1.5 1.5 0 0 0 4 20.5V5.5Z"
           :stroke "currentColor" :stroke-width "1.7" :stroke-linejoin "round"}]
   [:path {:d "M20 5.5C20 4.7 19.3 4 18.5 4H13v15h5.5a1.5 1.5 0 0 1 1.5 1.5V5.5Z"
           :stroke "currentColor" :stroke-width "1.7" :stroke-linejoin "round"}]])

(defn- nav-link [href label active? key]
  [:a (cond-> {:href href}
        (= active? key) (assoc :aria-current "page"))
   label])

(defn topbar
  "The shared masthead for signed-in pages: wordmark + primary nav, with
   `active` (a keyword like :queue, :authors, :sources, :settings) marking
   the current section. Sign-out is a POST, styled to read as a nav link."
  [active]
  [:header.topbar
   [:div.topbar-inner
    [:a.brand {:href "/"}
     [:span.brand-mark book-icon]
     [:span.brand-word "Reader"]]
    [:nav.topnav
     (nav-link "/" "Queue" active :queue)
     (nav-link "/authors" "Authors" active :authors)
     (nav-link "/affiliations" "Sources" active :sources)
     (nav-link "/settings" "Settings" active :settings)
     [:form.logout {:method "post" :action "/logout"}
      [:button {:type "submit"} "Sign out"]]]]])

(defn app-page
  "A full page for a signed-in view: the standard document plus the app shell
   (top bar with `active` section highlighted) wrapping `content` — the inner
   hiccup for <main>."
  [title active content]
  (page title
        (list (topbar active)
              [:main content])))

(defn not-found
  "The body for a 404, wrapped in the standard layout."
  [message]
  (page "Not found"
        [:main
         [:h1 "Not found"]
         [:p.muted message]
         [:p [:a {:href "/"} "Back to your reading list"]]]))

(defn forbidden
  "The body for a 403. Offers a sign-out so a signed-in but un-invited visitor
   isn't stuck — `/logout` is public, so it works even without access."
  [message]
  (page "Not allowed"
        [:main
         [:h1 "Not allowed"]
         [:p.muted message]
         [:form {:method "post" :action "/logout"}
          [:button {:type "submit"} "Sign out"]]]))

(defn server-error
  "The body for a 500. Deliberately static — it renders no request detail, since
   the exception middleware reaches here precisely when something is broken."
  []
  (page "Something went wrong"
        [:main
         [:h1 "Something went wrong"]
         [:p.muted "An unexpected error occurred. The problem has been logged."]
         [:p [:a {:href "/"} "Back to your reading list"]]]))
