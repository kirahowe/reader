(ns reader.dev.seed
  "Drops a coherent set of realistic fixtures into the dev database so
   `bb db:seed` produces something worth pointing a UI at. Two users with
   overlapping queues, so the per-user reading queue (reader.reading) has
   something real to show: some queue items point at the same underlying
   readable, held at different states by each user. Idempotent — truncates the
   seeded tables first, so re-running against an already-populated dev db is
   safe. Lives in `infra/src/` so it stays out of the prod uberjar."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
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

(defn- queue!
  "Add one queue item for `user`. `readable-type` is the stored string
   (\"article\" / \"paper\" / \"newsletter_issue\"); `via` records provenance."
  [tx user readable-type readable-id state via]
  (crud/create! tx :queue-items {:user-id       (:users/id user)
                                 :readable-type readable-type
                                 :readable-id   readable-id
                                 :state         state
                                 :via           via}))

(defn- seed-in-tx! [tx]
  (jdbc/execute! tx [(str "TRUNCATE " (str/join ", " seeded-tables) " CASCADE")])
  (let [;; Authors. sort-name omitted: these "First Last" bylines derive cleanly
        ;; via the heuristic in reader.authors/create!.
        didion  (authors/create! tx {:name "Joan Didion"
                                     :slug "joan-didion"
                                     :bio  "American essayist and novelist."})
        smith   (authors/create! tx {:name "Zadie Smith" :slug "zadie-smith"})
        mcphee  (authors/create! tx {:name "John McPhee" :slug "john-mcphee"})
        vaswani (authors/create! tx {:name "Ashish Vaswani" :slug "ashish-vaswani"})
        he      (authors/create! tx {:name "Kaiming He" :slug "kaiming-he"})

        ;; Affiliations: each readable's own source.
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

        ;; Readables: three articles, two papers, one newsletter issue.
        white     (crud/create! tx :articles
                                {:affiliation-id    (:affiliations/id ny)
                                 :title             "The White Album"
                                 :slug              "the-white-album"
                                 :canonical-url     "https://www.newyorker.com/the-white-album"
                                 :word-count        5200
                                 :reading-time-secs 1560
                                 :abstract          "On living in California in the late sixties."})
        slouching (crud/create! tx :articles
                                {:affiliation-id    (:affiliations/id ny)
                                 :title             "Slouching Towards Bethlehem"
                                 :slug              "slouching-towards-bethlehem"
                                 :canonical-url     "https://www.newyorker.com/slouching-towards-bethlehem"
                                 :word-count        7100
                                 :reading-time-secs 2130
                                 :abstract          "A portrait of the Haight-Ashbury in 1967."})
        marvin    (crud/create! tx :articles
                                {:affiliation-id    (:affiliations/id ny)
                                 :title             "The Search for Marvin Gardens"
                                 :slug              "the-search-for-marvin-gardens"
                                 :canonical-url     "https://www.newyorker.com/the-search-for-marvin-gardens"
                                 :word-count        6400
                                 :reading-time-secs 1920
                                 :abstract          "Monopoly, Atlantic City, and the board behind the game."})

        attention (crud/create! tx :papers
                                {:affiliation-id (:affiliations/id arxiv)
                                 :title          "Attention Is All You Need"
                                 :doi            "10.48550/arXiv.1706.03762"
                                 :arxiv-id       "1706.03762"
                                 :abstract       "The Transformer architecture."
                                 :pdf-object-key "papers/1706.03762.pdf"})
        resnet    (crud/create! tx :papers
                                {:affiliation-id (:affiliations/id arxiv)
                                 :title          "Deep Residual Learning for Image Recognition"
                                 :doi            "10.48550/arXiv.1512.03385"
                                 :arxiv-id       "1512.03385"
                                 :abstract       "Residual connections for very deep networks."
                                 :pdf-object-key "papers/1512.03385.pdf"})

        issue     (crud/create! tx :newsletter-issues
                                {:affiliation-id       (:affiliations/id act-nl)
                                 :subject              "ACT links for the week"
                                 :body-html            "<h1>This week</h1><p>…</p>"
                                 :raw-email-object-key "issues/act-2026-W21.eml"})

        ;; Users. test@example.com is the dev login (the lone address allowlisted
        ;; in env/dev/resources/dev.edn). Multi-user on purpose: the two queues
        ;; overlap (below).
        test-user (crud/create! tx :users {:email "test@example.com"  :display-name "Test User"})
        marcus    (crud/create! tx :users {:email "marcus@reader.test" :display-name "Marcus Chen"})]

    ;; Author <-> affiliation stints.
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

    ;; Bylines (the polymorphic readable <-> author bridge).
    (authorships/attach! tx {:author-id     (:authors/id didion)
                             :readable-type :article
                             :readable-id   (:articles/id white)
                             :ordinal       0})
    (authorships/attach! tx {:author-id     (:authors/id didion)
                             :readable-type :article
                             :readable-id   (:articles/id slouching)
                             :ordinal       0})
    (authorships/attach! tx {:author-id     (:authors/id mcphee)
                             :readable-type :article
                             :readable-id   (:articles/id marvin)
                             :ordinal       0})
    (authorships/attach! tx {:author-id     (:authors/id vaswani)
                             :readable-type :paper
                             :readable-id   (:papers/id attention)
                             :ordinal       0})
    (authorships/attach! tx {:author-id     (:authors/id he)
                             :readable-type :paper
                             :readable-id   (:papers/id resnet)
                             :ordinal       0})
    (authorships/attach! tx {:author-id         (:authors/id smith)
                             :readable-type     :newsletter-issue
                             :readable-id       (:newsletter-issues/id issue)
                             :ordinal           0
                             :contribution-type "guest"})

    ;; Inboxes.
    (crud/create! tx :email-inboxes {:user-id (:users/id test-user) :alias "test+1@inbox.reader.test"})
    (crud/create! tx :email-inboxes {:user-id (:users/id marcus)    :alias "marcus+1@inbox.reader.test"})

    ;; Queues. Both users have several items, and they SHARE two readables — the
    ;; White Album and the Attention paper — held at different states by each.
    ;; That's the whole point of queue_items being per-user over shared readables.
    (queue! tx test-user "article"          (:articles/id white)          "unread"  {:source "manual"})
    (queue! tx test-user "article"          (:articles/id marvin)         "unread"  {:source "manual"})
    (queue! tx test-user "paper"            (:papers/id attention)        "reading" {:source "import" :note "arXiv discovery"})
    (queue! tx test-user "newsletter_issue" (:newsletter-issues/id issue) "read"    {:source "email"})

    (queue! tx marcus "article"        (:articles/id white)     "reading" {:source "manual"})
    (queue! tx marcus "article"        (:articles/id slouching) "unread"  {:source "manual"})
    (queue! tx marcus "paper"          (:papers/id attention)   "unread"  {:source "import"})
    (queue! tx marcus "paper"          (:papers/id resnet)      "read"    {:source "import"})

    ;; A few durable jobs in flight.
    (jobs/enqueue! tx "thumbnails"    {:article-id (str (:articles/id white))})
    (jobs/enqueue! tx "extract-paper" {:paper-id   (str (:papers/id attention))})
    (jobs/enqueue! tx "extract-paper" {:paper-id   (str (:papers/id resnet))})))

(defn seed! [ds]
  (log/info "seed starting")
  (assert-local-db! ds)
  ;; One outer transaction so a partial failure rolls the whole seed
  ;; back. `:ignore` makes the inner `with-transaction` in
  ;; `authorships/attach!` join us instead of throwing on a nested tx.
  (binding [next.jdbc.transaction/*nested-tx* :ignore]
    (jdbc/with-transaction [tx ds]
      (seed-in-tx! tx)))
  (log/info "seed done"))
