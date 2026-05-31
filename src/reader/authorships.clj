(ns reader.authorships
  "Bridges authors and readables (articles, papers, newsletter issues)
   via a polymorphic FK that postgres cannot enforce. Every write goes
   through here so the readable target is validated in-process, and the
   check + insert run in a transaction with the readable row locked so
   a concurrent delete can't orphan the authorship."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [reader.db.crud :as crud]))

(def ^:private readables
  "What each app-side readable keyword resolves to: the table it lives
   in, plus the string stored in `authorships.readable_type`."
  {:article          {:table :articles           :type-str "article"}
   :paper            {:table :papers             :type-str "paper"}
   :newsletter-issue {:table :newsletter-issues  :type-str "newsletter_issue"}})

(defn- exists-and-lock!
  "Lock the readable row with SELECT … FOR UPDATE so a concurrent
   DELETE blocks until our authorship insert commits. Returns truthy
   iff the row exists."
  [tx table readable-id]
  (jdbc/execute-one! tx
                     (sql/format {:select [:id]
                                  :from   [table]
                                  :where  [:= :id readable-id]
                                  :for    [:update]})
                     crud/opts))

(defn attach!
  "Create an authorship linking `:author-id` to the readable identified
   by `:readable-type` (keyword) + `:readable-id`. Throws ExceptionInfo
   if the readable type is unknown or the row doesn't exist."
  [ds {:keys [author-id readable-type readable-id ordinal contribution-type]
       :or   {ordinal 0}}]
  (let [{:keys [table type-str]} (readables readable-type)]
    (when-not table
      (throw (ex-info "Unknown readable-type"
                      {:readable-type readable-type
                       :known         (set (keys readables))})))
    (jdbc/with-transaction [tx ds]
      (when-not (exists-and-lock! tx table readable-id)
        (throw (ex-info "Referenced readable does not exist"
                        {:readable-type readable-type
                         :readable-id   readable-id})))
      (crud/create! tx :authorships
                    (cond-> {:author-id     author-id
                             :readable-type type-str
                             :readable-id   readable-id
                             :ordinal       ordinal}
                      contribution-type (assoc :contribution-type contribution-type))))))
