(ns reader.ui.pages.home-test
  "Rendering tests for the reading-list page: tags surface as chip-links to their
   filtered view, the active filter is marked, and a tagless row stays clean."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
            [reader.ui.pages.home :as home]))

(defn- html [hiccup] (str (h/html hiccup)))

(deftest item-renders-tags-test
  (let [out (html (home/item {:queue-item-id (random-uuid) :title "B-Trees"
                              :active-tag "databases"
                              :tags [{:slug "databases" :label "databases"}
                                     {:slug "storage" :label "storage"}]}))]
    (testing "each tag renders as a chip-link to its filtered view"
      (is (str/includes? out "/?tag=databases"))
      (is (str/includes? out "/?tag=storage"))
      (is (str/includes? out ">databases</a>")))
    (testing "the active tag is marked, the others are not"
      (is (re-find #"chip--active[^>]*>databases" out))
      (is (not (re-find #"chip--active[^>]*>storage" out))))))

(deftest item-without-tags-test
  (testing "a row with no tags renders no tag strip"
    (is (not (str/includes? (html (home/item {:queue-item-id (random-uuid) :title "No tags"}))
                            "readable-tags")))))

(deftest render-filter-bar-test
  (let [items [{:queue-item-id (random-uuid) :title "A"
                :tags [{:slug "ml" :label "ml"}]}]
        ;; render returns a finished HTML string (layout/app-page), not raw hiccup.
        out   (home/render items "ml" [{:slug "ml" :label "ml"}
                                       {:slug "go" :label "go"}])]
    (testing "the filter bar offers All plus every tag, marking the active one"
      (is (str/includes? out "tag-filter"))
      (is (str/includes? out ">All</a>"))
      (is (str/includes? out "/?tag=go"))
      (is (re-find #"chip--active[^>]*>ml" out)))))
