(ns reader.extract
  "The extraction abstraction. Turns a stored readable (`reader.readables/find-one`
   payload) into a uniform content map the reader view renders blind to type:
   {:title :authors :source :date :body :links}. Polymorphic on the readable's
   :type.

   Article and newsletter-issue bodies render the stored HTML produced by
   ingest, which sanitizes with jsoup at the ingest boundary
   (reader.ingest.extract for articles, reader.ingest.email for newsletters) —
   so the stored body_html is trusted and rendered raw here. Until an article
   has been extracted (body_html is still null), and for papers, the body is a
   *placeholder* while the real fields (title / byline / source / date / links)
   come from what we already store. Paper PDF rendering lands with its own
   ingest path."
  (:require [hiccup.util :as hu]))

(defn- placeholder-body
  "Stand-in for a not-yet-rendered body. Shows `preview` (an abstract) when we
   have one, then a notice marking the full text as pending — an article still
   being fetched/parsed, or a paper whose in-reader PDF viewer isn't built yet."
  [preview]
  [:div.prose
   (when preview [:p.lead preview])
   [:p.muted.placeholder "The full text is not available in the reader yet."]])

(defn- article-body
  "An article's stored reader-view body when we have one — sanitized at ingest
   (reader.ingest.extract, jsoup Safelist), so the stored HTML is trusted and
   rendered raw — otherwise the placeholder previewing the abstract."
  [row]
  (if-let [html (not-empty (:articles/body-html row))]
    [:div.prose (hu/raw-string html)]
    (placeholder-body (:articles/abstract row))))

(defn- newsletter-body
  "A newsletter issue's stored body — sanitized at ingest (reader.ingest.email,
   jsoup Safelist), so the stored HTML is trusted and rendered raw — otherwise a
   placeholder (an issue with an empty body is unusual but renders cleanly)."
  [row]
  (if-let [html (not-empty (:newsletter-issues/body-html row))]
    [:div.prose (hu/raw-string html)]
    (placeholder-body nil)))

(defn- common
  "The type-independent fields, taken straight from the normalized item."
  [{:keys [item]}]
  {:title   (:title item)
   :authors (:authors item)
   :source  (:source item)})

(defmulti extract
  "A `reader.readables/find-one` payload ({:item :row}) -> the uniform content
   map {:title :authors :source :date :body :links}. Dispatches on readable :type."
  (fn [{:keys [item]}] (:type item)))

(defmethod extract :article [{:keys [row] :as readable}]
  (assoc (common readable)
         :date  (:articles/published-at row)
         :body  (article-body row)
         :links [{:label "View original" :href (:articles/canonical-url row)}]))

(defmethod extract :paper [{:keys [row] :as readable}]
  (assoc (common readable)
         :date  (:papers/published-at row)
         :body  (placeholder-body (:papers/abstract row))
         :links (cond-> []
                  (:papers/doi row)
                  (conj {:label "DOI" :href (str "https://doi.org/" (:papers/doi row))})
                  (:papers/arxiv-id row)
                  (conj {:label "arXiv" :href (str "https://arxiv.org/abs/" (:papers/arxiv-id row))}))))

(defmethod extract :newsletter-issue [{:keys [row] :as readable}]
  (assoc (common readable)
         :date            (:newsletter-issues/sent-at row)
         :body            (newsletter-body row)
         :unsubscribe-url (:newsletter-issues/unsubscribe-url row)
         :links           []))
