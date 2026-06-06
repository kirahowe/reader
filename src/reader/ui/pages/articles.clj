(ns reader.ui.pages.articles
  "The add-article form."
  (:require [reader.ui.layout :as layout]))

(defn- text-field [label input-name value error]
  [:label
   [:span.label-text label]
   [:input {:type "text" :name input-name :value value}]
   (when error [:span.error (first error)])])

(defn- source-select [affiliations selected]
  [:label
   [:span.label-text "Source"]
   (into [:select {:name "affiliation-id"}
          [:option {:value ""} "— none —"]]
         (map (fn [a]
                (let [id (str (:affiliations/id a))]
                  [:option (cond-> {:value id}
                             (= id selected) (assoc :selected true))
                   (:affiliations/name a)]))
              affiliations))])

(defn new-form
  "The add-article form. `affiliations` fills the source select; `values`
   (the submitted params) and `errors` repopulate it after a rejected submit."
  ([affiliations] (new-form affiliations {} nil))
  ([affiliations values errors]
   (layout/page
    "Add article"
    [:main
     [:nav.index-nav.muted [:a {:href "/"} "Back to your reading list"]]
     [:h1 "Add article"]
     [:form.form {:method "post" :action "/articles"}
      (text-field "Title" "title" (get values "title") (:title errors))
      (text-field "URL" "canonical-url" (get values "canonical-url") (:canonical-url errors))
      (source-select affiliations (get values "affiliation-id"))
      [:label
       [:span.label-text "Abstract"]
       [:textarea {:name "abstract"} (get values "abstract")]]
      [:button {:type "submit"} "Add to reading list"]]])))
