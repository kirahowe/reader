(ns reader.eval.queue
  "The Workbench labeling queue (ADR 0006): the next unlabeled case to judge, and
   the progress counts, per pipeline. Unlabeled cases are ordered failures-first
   then newest, so the most informative ones get labeled first. A case carries
   exactly what the Workbench renders: the content plus the flaggable items
   (assigned tags / resolved byline) with their slugs."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [reader.eval.inspect :as inspect]))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})
(defn- q  [ds m] (jdbc/execute!     ds (sql/format m) opts))
(defn- q1 [ds m] (jdbc/execute-one! ds (sql/format m) opts))

(defn- n [ds m] (:n (q1 ds m)))

(def ^:private low-conf-threshold
  "Average baseline confidence below which an unlabeled case is worth labeling
   sooner — the model was unsure, so a human read is more informative."
  0.6)

;; ── tagging ──────────────────────────────────────────────────────────────────

;; Average baseline confidence per readable, one row each — left-joined so a
;; readable's confidence is available without the cartesian blow-up a direct
;; join to readable_tags would cause inside the grouped events query.
(def ^:private tag-conf-sub
  {:select   [:readable-type :readable-id [[:avg :confidence] :avg-conf]]
   :from     [:readable-tags]
   :group-by [:readable-type :readable-id]})

(defn tagging-progress
  "{:total :labeled :failed :low-conf} over the readables that have a tagging
   attempt. `total` counts distinct readables; `labeled` those with a golden
   label; `failed` unlabeled readables whose latest attempt failed; `low-conf`
   unlabeled, non-failed readables whose average baseline confidence is low."
  [ds]
  (let [total   (n ds {:select [[[:count [:distinct [:composite :readable-type :readable-id]]] :n]]
                       :from   [:tagging-events]})
        labeled (n ds {:select [[[:count :*] :n]] :from [:eval-labels] :where [:= :feature "tagging"]})
        failed  (n ds {:select   [[[:count :*] :n]]
                       :from     [[{:select    [:te.readable-type :te.readable-id]
                                    :from      [[:tagging-events :te]]
                                    :left-join [[:eval-labels :el]
                                                [:and [:= :el.feature "tagging"]
                                                 [:= :el.readable-type :te.readable-type]
                                                 [:= :el.readable-id :te.readable-id]]]
                                    :where     [:= :el.id nil]
                                    :group-by  [:te.readable-type :te.readable-id]
                                    :having    [:bool_or [:= :te.outcome "failed"]]} :f]]})
        low     (n ds {:select   [[[:count :*] :n]]
                       :from     [[{:select    [:te.readable-type :te.readable-id]
                                    :from      [[:tagging-events :te]]
                                    :left-join [[:eval-labels :el]
                                                [:and [:= :el.feature "tagging"]
                                                 [:= :el.readable-type :te.readable-type]
                                                 [:= :el.readable-id :te.readable-id]]
                                                [tag-conf-sub :rc]
                                                [:and [:= :rc.readable-type :te.readable-type]
                                                 [:= :rc.readable-id :te.readable-id]]]
                                    :where     [:= :el.id nil]
                                    :group-by  [:te.readable-type :te.readable-id]
                                    :having    [:and [:not [:bool_or [:= :te.outcome "failed"]]]
                                                [:< [:min :rc.avg-conf] low-conf-threshold]]} :f]]})]
    {:total total :labeled labeled :failed failed :low-conf low}))

(defn- next-tagging-ref [ds exclude]
  (q1 ds {:select    [:te.readable-type :te.readable-id]
          :from      [[:tagging-events :te]]
          :left-join [[:eval-labels :el]
                      [:and [:= :el.feature "tagging"]
                       [:= :el.readable-type :te.readable-type]
                       [:= :el.readable-id :te.readable-id]]
                      [tag-conf-sub :rc]
                      [:and [:= :rc.readable-type :te.readable-type]
                       [:= :rc.readable-id :te.readable-id]]]
          :where     (if (seq exclude)
                       [:and [:= :el.id nil] [:not [:in :te.readable-id exclude]]]
                       [:= :el.id nil])
          :group-by  [:te.readable-type :te.readable-id]
          ;; failures first, then lowest-confidence, then newest.
          :order-by  [[[:bool_or [:= :te.outcome "failed"]] :desc]
                      [[:coalesce [:min :rc.avg-conf] [:inline 1]] :asc]
                      [[:max :te.created-at] :desc]]
          :limit     1}))

(defn- assigned-tags [ds rtype rid]
  (q ds {:select   [:t.slug :t.label]
         :from     [[:readable-tags :rt]]
         :join     [[:tags :t] [:= :t.id :rt.tag-id]]
         :where    [:and [:= :rt.readable-type rtype] [:= :rt.readable-id rid]]
         :order-by [[:t.label :asc]]}))

(defn tagging-next
  "The next tagging case to label, or nil when the queue is empty. `:exclude` is
   a set of readable-ids to skip past this session."
  ([ds] (tagging-next ds {}))
  ([ds {:keys [exclude]}]
   (when-let [{:keys [readable-type readable-id]} (next-tagging-ref ds exclude)]
     (let [{:keys [labeled total] :as progress} (tagging-progress ds)
           summary (inspect/readable-summary ds readable-type readable-id)]
       {:readable-type readable-type
        :readable-id   readable-id
        :title         (:title summary)
        :excerpt       (:abstract summary)
        :tags          (assigned-tags ds readable-type readable-id)
        :queue         progress
        :position      (inc labeled)
        :total         total}))))

;; ── extraction ────────────────────────────────────────────────────────────────

(defn extraction-progress
  [ds]
  (let [unlabeled  {:from      [[:extraction-events :ee]]
                    :left-join [[:eval-labels :el]
                                [:and [:= :el.feature "extraction"] [:= :el.subject-url :ee.url]]]
                    :where     [:= :el.id nil]
                    :group-by  [:ee.url]}
        total   (n ds {:select [[[:count [:distinct :url]] :n]] :from [:extraction-events]})
        labeled (n ds {:select [[[:count :*] :n]] :from [:eval-labels] :where [:= :feature "extraction"]})
        failed  (n ds {:select [[[:count :*] :n]]
                       :from   [[(assoc unlabeled :select [:ee.url]
                                        :having [:bool_or [:= :ee.outcome "failed"]]) :f]]})
        low     (n ds {:select [[[:count :*] :n]]
                       :from   [[(assoc unlabeled :select [:ee.url]
                                        :having [:and [:not [:bool_or [:= :ee.outcome "failed"]]]
                                                 [:< [:min :ee.body-confidence] low-conf-threshold]]) :f]]})]
    {:total total :labeled labeled :failed failed :low-conf low}))

(defn- next-extraction-url [ds exclude]
  (:url (q1 ds {:select    [:ee.url]
                :from      [[:extraction-events :ee]]
                :left-join [[:eval-labels :el]
                            [:and [:= :el.feature "extraction"] [:= :el.subject-url :ee.url]]]
                :where     (if (seq exclude)
                             [:and [:= :el.id nil] [:not [:in :ee.url exclude]]]
                             [:= :el.id nil])
                :group-by  [:ee.url]
                :order-by  [[[:bool_or [:= :ee.outcome "failed"]] :desc]
                            [[:coalesce [:min :ee.body-confidence] [:inline 1]] :asc]
                            [[:max :ee.created-at] :desc]]
                :limit     1})))

(defn- byline [ds url]
  (when-let [art (q1 ds {:select [:id :title] :from [:articles] :where [:= :canonical-url url]})]
    {:article art
     :authors (q ds {:select   [:a.slug :a.name :au.ordinal]
                     :from     [[:authorships :au]]
                     :join     [[:authors :a] [:= :a.id :au.author-id]]
                     :where    [:and [:= :au.readable-type "article"] [:= :au.readable-id (:id art)]]
                     :order-by [[:au.ordinal :asc]]})}))

(defn- source-of [ds url]
  (q1 ds {:select [:aff.slug :aff.name :aff.type]
          :from   [[:articles :ar]]
          :join   [[:affiliations :aff] [:= :aff.id :ar.affiliation-id]]
          :where  [:= :ar.canonical-url url]}))

(defn extraction-next
  "The next extraction case to label, or nil when the queue is empty. `:exclude`
   is a set of subject-urls to skip past this session."
  ([ds] (extraction-next ds {}))
  ([ds {:keys [exclude]}]
   (when-let [url (next-extraction-url ds exclude)]
     (let [{:keys [labeled total] :as progress} (extraction-progress ds)
           {:keys [article authors]} (byline ds url)]
       {:subject-url url
        :title       (:title article)
        :byline      (vec authors)
        :source      (source-of ds url)
        :queue       progress
        :position    (inc labeled)
        :total       total}))))
