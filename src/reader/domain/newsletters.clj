(ns reader.domain.newsletters
  "Newsletter persistence. Delivery Message-IDs remain the idempotency key, while
   normalized newsletter identity, authors, source, and extraction provenance are
   replaced atomically when a newer parser version is applied."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [reader.db.crud :as crud]
            [reader.domain.authors :as authors]
            [reader.domain.reading :as reading]
            [reader.jobs :as jobs]
            [reader.util.slug :as slug])
  (:import (java.time Instant)))

(defn- domain-of [email]
  (some-> email str/trim (str/split #"@") second str/lower-case not-empty))

(defn- sld-name
  "A conservative display name derived from a sender domain."
  [domain]
  (let [labels (str/split domain #"\.")
        sld    (if (>= (count labels) 2) (nth labels (- (count labels) 2)) (first labels))]
    (str/capitalize sld)))

(defn- alias-for [from-email source]
  (or (some-> (:sender-alias source) str/trim str/lower-case not-empty)
      (some->> (domain-of from-email) (str "@"))))

(defn- fallback-source-name [pattern]
  (if-let [domain (some-> pattern (str/replace-first #"^@" "") not-empty)]
    (sld-name domain)
    "Unknown newsletter"))

(defn- enrich-source!
  "Fill higher-confidence newsletter metadata without changing a stable slug.
   A domain-derived placeholder name may be replaced; user/curated names are not."
  [tx aff source pattern]
  (let [fallback (fallback-source-name pattern)
        updates  (cond-> {:type "newsletter" :updated-at (Instant/now)}
                   (and (not-empty (:name source))
                        (= fallback (:affiliations/name aff)))
                   (assoc :name (:name source))

                   (and (not-empty (:url source)) (nil? (:affiliations/url aff)))
                   (assoc :url (:url source)))]
    (crud/update! tx :affiliations (:affiliations/id aff) updates)))

(defn source-for!
  "Resolve a newsletter affiliation from an extracted sender alias and source.
   Aliases are many-to-one and race-safe. Existing domain-derived placeholder
   names are enriched from high-confidence newsletter metadata."
  ([tx from-email] (source-for! tx from-email nil))
  ([tx from-email source]
   (let [pattern  (alias-for from-email source)
         existing (when pattern
                    (crud/find-1 tx :newsletter-source-aliases {:alias pattern}))]
     (if existing
       (let [aff (crud/by-id tx :affiliations (:newsletter-source-aliases/affiliation-id existing))]
         (crud/update-where! tx :newsletter-source-aliases [:= :alias pattern]
                             {:last-seen-at (Instant/now)})
         (enrich-source! tx aff source pattern))
       (let [nm      (or (not-empty (:name source)) (fallback-source-name pattern))
             created (crud/upsert! tx :affiliations
                                   (cond-> {:name nm :slug (slug/slugify nm) :type "newsletter"}
                                     (not-empty (:url source)) (assoc :url (:url source)))
                                   [:slug] {:updated-at [:now]})
             aff     (enrich-source! tx created source pattern)
             winner  (if pattern
                       (let [linked (crud/upsert! tx :newsletter-source-aliases
                                                  {:alias pattern
                                                   :affiliation-id (:affiliations/id aff)
                                                   :last-seen-at (Instant/now)}
                                                  [:alias] {:last-seen-at [:now]})]
                         (->> (:newsletter-source-aliases/affiliation-id linked)
                              (crud/by-id tx :affiliations)))
                       aff)
             resolved (enrich-source! tx winner source pattern)]
         ;; Keep the original 1:1 extension populated for compatibility; the
         ;; alias relation is authoritative and can hold every valid sender.
         (crud/upsert! tx :newsletter-sources
                       {:affiliation-id      (:affiliations/id resolved)
                        :inbound-email-alias pattern
                        :last-seen-at        (Instant/now)}
                       [:affiliation-id] {:last-seen-at [:now]})
         resolved)))))

(defn- clear-authorships! [tx issue-id]
  (jdbc/execute! tx
                 (sql/format {:delete-from :authorships
                              :where       [:and
                                            [:= :readable-type "newsletter_issue"]
                                            [:= :readable-id issue-id]]})))

(defn- attach-authors! [tx issue-id extracted-authors]
  (doseq [[ordinal author-data] (map-indexed vector extracted-authors)
          :when (not-empty (:name author-data))]
    (let [author (authors/find-or-create! tx (:name author-data) (:url author-data))]
      (crud/create! tx :authorships {:author-id     (:authors/id author)
                                     :readable-type "newsletter_issue"
                                     :readable-id   issue-id
                                     :ordinal       ordinal}))))

(defn- issue-attrs [parsed affiliation-id]
  {:affiliation-id       affiliation-id
   :subject              (or (not-empty (:subject parsed)) "(no subject)")
   :body-html            (or (:body-html parsed) "")
   :sent-at              (:sent-at parsed)
   :raw-email-object-key (:raw-key parsed)
   :message-id           (:message-id parsed)
   :unsubscribe-url      (:unsubscribe-url parsed)
   :original-message-id  (get-in parsed [:newsletter :source-message-id])
   :original-from-name   (:from-name parsed)
   :original-from-email  (:from-email parsed)
   :original-url         (:original-url parsed)
   :is-forwarded         (boolean (:forwarded? parsed))
   :extraction-version   (or (:extraction-version parsed) 1)
   :extraction-provenance (or (:provenance parsed) {})
   :updated-at           (Instant/now)})

(defn- lock-issue [tx issue-id]
  (jdbc/execute-one! tx
                     (sql/format {:select [:*] :from [:newsletter-issues]
                                  :where [:= :id issue-id] :for [:update]})
                     crud/opts))

(defn apply-extraction!
  "Atomically replace an issue's normalized fields and authorships when `parsed`
   is newer than the stored extraction. Returns {:issue row :applied? boolean}.
   `:force?` permits an explicit same-version repair; ordinary retries are no-ops."
  ([tx issue-id parsed] (apply-extraction! tx issue-id parsed {}))
  ([tx issue-id parsed {:keys [force?]}]
   (let [current (or (lock-issue tx issue-id)
                     (throw (ex-info "newsletter issue not found"
                                     {:newsletter-issue-id issue-id
                                      :error-class :missing-readable :fatal? true})))
         current-version (or (:newsletter-issues/extraction-version current) 1)
         next-version    (or (:extraction-version parsed) 1)]
     (if-not (or (< current-version next-version)
                 (and force? (= current-version next-version)))
       {:issue current :applied? false}
       (let [aff   (source-for! tx (:from-email parsed) (:source parsed))
             attrs (issue-attrs (assoc parsed
                                       :raw-key (or (:raw-key parsed)
                                                    (:newsletter-issues/raw-email-object-key current))
                                       :message-id (or (:message-id parsed)
                                                       (:newsletter-issues/message-id current)))
                                (:affiliations/id aff))
             row   (crud/update! tx :newsletter-issues issue-id attrs)]
         (clear-authorships! tx issue-id)
         (attach-authors! tx issue-id (:authors parsed))
         (jobs/enqueue! tx "tag-readable"
                        {:readable-type "newsletter_issue" :readable-id issue-id
                         :content-version next-version})
         {:issue row :applied? true})))))

(defn- create-issue! [tx parsed]
  (let [aff   (source-for! tx (:from-email parsed) (:source parsed))
        attrs (issue-attrs parsed (:affiliations/id aff))
        issue (if (:message-id parsed)
                (crud/create-ignore! tx :newsletter-issues attrs)
                (crud/create! tx :newsletter-issues attrs))]
    (when issue
      (attach-authors! tx (:newsletter-issues/id issue) (:authors parsed))
      (jobs/enqueue! tx "tag-readable"
                     {:readable-type "newsletter_issue" :readable-id (:newsletter-issues/id issue)
                      :content-version (or (:extraction-version parsed) 1)}))
    issue))

(defn record-issue!
  "Find-or-create a delivery by outer Message-ID, safely apply a newer extraction
   to an existing row, and queue it for the addressed user without resurrecting
   a read/archived queue item. Returns the queue item."
  [tx user-id parsed]
  (let [message-id (:message-id parsed)
        existing   (when message-id
                     (crud/find-1 tx :newsletter-issues {:message-id message-id}))
        created    (when-not existing (create-issue! tx parsed))
        stored     (or existing created
                       ;; Lost a concurrent create-ignore race; resolve the winner.
                       (when message-id
                         (crud/find-1 tx :newsletter-issues {:message-id message-id})))
        normalized (:issue (if created
                             {:issue stored}
                             (apply-extraction! tx (:newsletter-issues/id stored) parsed)))]
    (reading/enqueue-if-absent! tx user-id "newsletter_issue" (:newsletter-issues/id normalized)
                                {:source "email" :from (:from-email parsed)})))
