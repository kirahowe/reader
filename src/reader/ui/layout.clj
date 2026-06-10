(ns reader.ui.layout
  (:require [hiccup2.core :as h]))

(defn page
  "Wraps `body` in a complete HTML document with the standard head and
   stylesheets. `body` is a hiccup form that becomes the contents of
   <body>."
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
