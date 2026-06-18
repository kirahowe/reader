(ns reader.ui.pages.articles
  "The add-article form."
  (:require [reader.ui.components :as c]
            [reader.ui.layout :as layout]))

(defn- source-options
  "Affiliations as select options, with a leading “none” choice."
  [affiliations]
  (cons {:value "" :label "— none —"}
        (map (fn [a] {:value (str (:affiliations/id a))
                      :label (:affiliations/name a)})
             affiliations)))

(defn new-form
  "The add-article form. `affiliations` fills the source select; `values`
   (the submitted params) and `errors` repopulate it after a rejected submit."
  ([affiliations] (new-form affiliations {} nil))
  ([affiliations values errors]
   (layout/app-page
    "Add article" nil
    (list
     (c/back-link "/")
     (c/page-head "Add article")
     [:form.form {:method "post" :action "/articles"}
      (c/text-field "Title" "title" (get values "title") (:title errors))
      (c/text-field "URL" "canonical-url" (get values "canonical-url") (:canonical-url errors) "url")
      (c/select-field "Source" "affiliation-id" (source-options affiliations)
                      (get values "affiliation-id") (:affiliation-id errors))
      (c/textarea-field "Abstract" "abstract" (get values "abstract") nil)
      (c/button {:type "submit" :variant :primary} "Add to reading list")]))))
