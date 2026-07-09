(ns reader.ui.layout
  (:require [hiccup2.core :as h]
            [reader.ui.components :as c]))

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
          ;; viewport-fit=cover lets the shell paint into the notch/home-bar
          ;; areas on phones; safe-area insets in main.css keep content clear.
          [:meta {:name "viewport"
                  :content "width=device-width, initial-scale=1, viewport-fit=cover"}]
          [:meta {:name "theme-color" :media "(prefers-color-scheme: light)" :content "#f7f4ec"}]
          [:meta {:name "theme-color" :media "(prefers-color-scheme: dark)" :content "#161613"}]
          [:title title]
          [:link {:rel "stylesheet" :href "/static/css/tokens.css"}]
          [:link {:rel "stylesheet" :href "/static/css/main.css"}]
          ;; Datastar: server-authoritative reactivity over SSE. Forms are
          ;; intercepted per-element (data-on-submit) and the server patches
          ;; the DOM; without JS every form still posts and redirects.
          [:script {:type "module" :src "/static/js/datastar.js"}]]
         [:body body]])))

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
     [:span.brand-mark c/icon-book]
     [:span.brand-word "Reader"]]
    [:nav.topnav
     (nav-link "/" "Queue" active :queue)
     (nav-link "/authors" "Authors" active :authors)
     (nav-link "/affiliations" "Sources" active :sources)
     (nav-link "/settings" "Settings" active :settings)
     [:form.logout {:method "post" :action "/logout"}
      (c/button {:type "submit" :variant :link} "Sign out")]]]])

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
         [:p [:a {:href "/"} "Back to your queue"]]]))

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
         [:p [:a {:href "/"} "Back to your queue"]]]))
