(ns reader.ui.components
  "Small shared hiccup fragments used across page namespaces.")

(defn author-link [{:keys [name slug]}]
  [:a {:href (str "/authors/" slug)} name])

(defn byline
  "A comma-separated span of author links, or nil when there are no authors.
   `authors` is a seq of {:name :slug} maps."
  [authors]
  (when (seq authors)
    (into [:span] (interpose ", " (map author-link authors)))))
