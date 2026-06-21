(ns reader.domain.newsletters
  "Newsletter domain: resolve the sending affiliation (a newsletter source keyed
   on its sender-domain pattern) and record an inbound issue as a queued
   readable. Idempotent on the email Message-ID so a redelivery doesn't
   double-ingest. All writes assume an open transaction `tx`."
  (:require [clojure.string :as str]
            [reader.domain.authors :as authors]
            [reader.db.crud :as crud]
            [reader.jobs :as jobs]
            [reader.domain.reading :as reading]
            [reader.util.slug :as slug])
  (:import (java.time Instant)))

(defn- domain-of [email]
  (some-> email str/trim (str/split #"@") second str/lower-case not-empty))

(defn- sld-name
  "A display name for a sender domain: the second-level label, capitalized
   (stratechery.com -> Stratechery). A reasonable publication name when the From
   display name is the author rather than the outlet."
  [domain]
  (let [labels (str/split domain #"\.")
        sld    (if (>= (count labels) 2) (nth labels (- (count labels) 2)) (first labels))]
    (str/capitalize sld)))

(defn source-for!
  "The affiliation (a type='newsletter' outlet) for `from-email`, matched on its
   domain via newsletter_sources.inbound_email_alias and created on first sight,
   named from the domain. Returns the affiliation row. Race/collision-safe via
   upsert."
  [tx from-email]
  (let [domain  (domain-of from-email)
        pattern (some->> domain (str "@"))]
    (or (when pattern
          (when-let [src (crud/find-1 tx :newsletter-sources {:inbound-email-alias pattern})]
            (crud/by-id tx :affiliations (:newsletter-sources/affiliation-id src))))
        (let [nm  (if domain (sld-name domain) "Unknown newsletter")
              aff (crud/upsert! tx :affiliations
                                {:name nm :slug (slug/slugify nm) :type "newsletter"}
                                [:slug] {:updated-at [:now]})]
          (when pattern
            (crud/upsert! tx :newsletter-sources
                          {:affiliation-id      (:affiliations/id aff)
                           :inbound-email-alias pattern
                           :last-seen-at        (Instant/now)}
                          [:affiliation-id] {:last-seen-at [:now]}))
          aff))))

(defn- author-name [from-name from-email]
  (or (not-empty from-name)
      (some-> from-email (str/split #"@") first not-empty)
      "Unknown"))

(defn record-issue!
  "Find-or-create the newsletter issue for `message-id` (idempotent — a
   redelivery reuses the existing issue, and attaches the author only on first
   creation), then queue it for `user-id` if it isn't already there. A redelivery
   never disturbs a queue item the user has already read or archived. Returns the
   queue item."
  [tx user-id {:keys [subject body-html sent-at message-id raw-key from-name from-email unsubscribe-url]}]
  (let [issue
        (or (when message-id (crud/find-1 tx :newsletter-issues {:message-id message-id}))
            (let [aff    (source-for! tx from-email)
                  iss    (crud/create! tx :newsletter-issues
                                       {:affiliation-id       (:affiliations/id aff)
                                        :subject              (or (not-empty subject) "(no subject)")
                                        :body-html            (or body-html "")
                                        :sent-at              sent-at
                                        :raw-email-object-key raw-key
                                        :message-id           message-id
                                        :unsubscribe-url      unsubscribe-url})
                  author (authors/find-or-create! tx (author-name from-name from-email))]
              (crud/create! tx :authorships {:author-id     (:authors/id author)
                                             :readable-type "newsletter_issue"
                                             :readable-id   (:newsletter-issues/id iss)
                                             :ordinal       0})
              (jobs/enqueue! tx "tag-readable"
                             {:readable-type "newsletter_issue" :readable-id (:newsletter-issues/id iss)})
              iss))]
    (reading/enqueue-if-absent! tx user-id "newsletter_issue" (:newsletter-issues/id issue)
                                {:source "email" :from from-email})))
