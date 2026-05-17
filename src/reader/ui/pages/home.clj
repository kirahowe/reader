(ns reader.ui.pages.home
  (:require [reader.ui.layout :as layout]))

(defn render []
  (layout/page
   "Reader"
   [:main
    [:h1 "Reader"]
    [:p "A quiet place for things you want to read."]
    [:p.muted "v0.1"]]))
