(ns reader.extract
  "The extraction seam. Turns a stored readable (`reader.readables/find-one`
   payload) into a uniform content map the reader view renders blind to type:
   {:title :authors :source :date :body :links}. Polymorphic on the readable's
   :type.

   Real per-type body rendering is deferred and looks different per type:
   articles/papers still need fetching and parsing, while a newsletter issue's
   full content is already stored and only needs sanitization. For now each
   method returns a *placeholder* body while populating the real fields
   (title/byline/source/date/links) from what we already store. Because the
   placeholder is plain escaped text, there is no raw-HTML/XSS surface yet;
   sanitization rides with the real body rendering.")

(defn- placeholder-body
  "Stand-in for a not-yet-rendered body. Shows `preview` (an abstract) when we
   have one, then a notice marking the full text as pending. What's pending
   varies by type: fetching/parsing for articles and papers, sanitization for
   stored newsletter HTML. TODO: real per-type rendering replaces this in the
   `extract` methods below."
  [preview]
  [:div.prose
   (when preview [:p.lead preview])
   [:p.muted.placeholder "The full text is not available in the reader yet."]])

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
         :body  (placeholder-body (:articles/abstract row))
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
         :date  (:newsletter-issues/sent-at row)
         :body  (placeholder-body nil)
         :links []))
