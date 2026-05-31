(ns reader.dev.seed
  "Drops a coherent set of realistic fixtures into the dev database so
   `bb db:seed` produces something worth pointing a UI at. Idempotent —
   truncates the seeded tables first, so re-running against an
   already-populated dev db is safe. Lives in `infra/src/` so it stays
   out of the prod uberjar."
  (:require [clojure.string :as str]
            [com.brunobonacci.mulog :as mu]
            [next.jdbc :as jdbc]
            [next.jdbc.transaction]
            [reader.authors :as authors]
            [reader.authorships :as authorships]
            [reader.db.crud :as crud]
            [reader.jobs :as jobs]))

(def ^:private seeded-tables
  ["authors" "affiliations" "author_affiliations" "newsletter_sources"
   "articles" "papers" "newsletter_issues" "authorships" "users"
   "email_inboxes" "queue_items" "jobs"])

(def ^:private local-hosts #{"localhost" "127.0.0.1" "::1" "[::1]"})

(defn- assert-local-url!
  "Throw unless `url` points at a local Postgres. `seed-in-tx!` opens with
   `TRUNCATE ... CASCADE`, which would wipe a real database if `ds` were
   ever pointed at a hosted (prod) datasource. The dev/test embedded
   Postgres always listens on localhost, so a non-local host is a sign the
   seeder is aimed at the wrong place — fail loudly before truncating."
  [url]
  (let [host (some-> (re-find #"jdbc:postgresql://([^:/]+)" url) second)]
    (when-not (contains? local-hosts host)
      (throw (ex-info (str "reader.dev.seed/seed! refuses to run against a "
                           "non-local database — it TRUNCATEs every seeded table")
                      {:host host :jdbc-url url})))))

(defn- assert-local-db! [ds]
  (assert-local-url! (with-open [conn (jdbc/get-connection ds)]
                       (.. conn getMetaData getURL))))

(defn- seed-in-tx! [tx]
  (jdbc/execute! tx [(str "TRUNCATE " (str/join ", " seeded-tables) " CASCADE")])
  ;; sort-name omitted: these "First Last" bylines derive cleanly via the
  ;; heuristic in reader.authors/create!.
  (let [didion  (authors/create! tx {:name "Joan Didion"
                                     :slug "joan-didion"
                                     :bio  "American essayist and novelist."})
        smith   (authors/create! tx {:name "Zadie Smith" :slug "zadie-smith"})
        mcphee  (authors/create! tx {:name "John McPhee" :slug "john-mcphee"})

        ny      (crud/create! tx :affiliations {:name "The New Yorker"
                                                :slug "the-new-yorker"
                                                :type "magazine"
                                                :url  "https://www.newyorker.com"})
        act-nl  (crud/create! tx :affiliations {:name "Astral Codex Ten Newsletter"
                                                :slug "act-newsletter"
                                                :type "newsletter"})
        arxiv   (crud/create! tx :affiliations {:name "arXiv"
                                                :slug "arxiv"
                                                :type "preprint"
                                                :url  "https://arxiv.org"})

        article (crud/create! tx :articles
                              {:affiliation-id    (:affiliations/id ny)
                               :title             "The White Album"
                               :slug              "the-white-album"
                               :canonical-url     "https://www.newyorker.com/the-white-album"
                               :word-count        5200
                               :reading-time-secs 1560
                               :abstract          "On living in California in the late sixties."})
        paper   (crud/create! tx :papers
                              {:affiliation-id (:affiliations/id arxiv)
                               :title          "Attention Is All You Need"
                               :doi            "10.48550/arXiv.1706.03762"
                               :arxiv-id       "1706.03762"
                               :abstract       "The Transformer architecture."
                               :pdf-object-key "papers/1706.03762.pdf"})
        issue   (crud/create! tx :newsletter-issues
                              {:affiliation-id       (:affiliations/id act-nl)
                               :subject              "ACT links for the week"
                               :body-html            "<h1>This week</h1><p>…</p>"
                               :raw-email-object-key "issues/act-2026-W21.eml"})

        user    (crud/create! tx :users {:email        "kira@reader.test"
                                         :display-name "Kira"})]

    (crud/create! tx :author-affiliations {:author-id      (:authors/id didion)
                                           :affiliation-id (:affiliations/id ny)
                                           :role           "staff writer"
                                           :is-primary     true})
    (crud/create! tx :author-affiliations {:author-id      (:authors/id mcphee)
                                           :affiliation-id (:affiliations/id ny)
                                           :role           "staff writer"
                                           :is-primary     true})

    (crud/create! tx :newsletter-sources {:affiliation-id      (:affiliations/id act-nl)
                                          :inbound-email-alias "act@inbox.reader.test"})

    (authorships/attach! tx {:author-id     (:authors/id didion)
                             :readable-type :article
                             :readable-id   (:articles/id article)
                             :ordinal       0})
    (authorships/attach! tx {:author-id         (:authors/id smith)
                             :readable-type     :newsletter-issue
                             :readable-id       (:newsletter-issues/id issue)
                             :ordinal           0
                             :contribution-type "guest"})

    (crud/create! tx :email-inboxes {:user-id (:users/id user)
                                     :alias   "kira+1@inbox.reader.test"})

    (crud/create! tx :queue-items {:user-id       (:users/id user)
                                   :readable-type "article"
                                   :readable-id   (:articles/id article)
                                   :via           {:source "manual"}})
    (crud/create! tx :queue-items {:user-id       (:users/id user)
                                   :readable-type "paper"
                                   :readable-id   (:papers/id paper)
                                   :state         "reading"
                                   :via           {:source "import" :note "arXiv discovery"}})
    (crud/create! tx :queue-items {:user-id       (:users/id user)
                                   :readable-type "newsletter_issue"
                                   :readable-id   (:newsletter-issues/id issue)
                                   :state         "read"
                                   :via           {:source "email"}})

    (jobs/enqueue! tx "thumbnails"    {:article-id (str (:articles/id article))})
    (jobs/enqueue! tx "extract-paper" {:paper-id   (str (:papers/id paper))})))

(defn seed! [ds]
  (mu/log ::starting)
  (assert-local-db! ds)
  ;; One outer transaction so a partial failure rolls the whole seed
  ;; back. `:ignore` makes the inner `with-transaction` in
  ;; `authorships/attach!` join us instead of throwing on a nested tx.
  (binding [next.jdbc.transaction/*nested-tx* :ignore]
    (jdbc/with-transaction [tx ds]
      (seed-in-tx! tx)))
  (mu/log ::done))
