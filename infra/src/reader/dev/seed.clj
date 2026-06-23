(ns reader.dev.seed
  "Drops a coherent set of realistic fixtures into the dev database so
   `bb db:seed` produces something worth pointing a UI at. Two users with
   overlapping queues, so the per-user reading queue (reader.domain.reading) has
   something real to show: some queue items point at the same underlying
   readable, held at different states by each user.

   Hybrid by design: the bulk is a direct-seeded library of already-extracted
   readables (instant content, no jobs), but `seed-live-jobs!` also drives one
   real job of each type through its production entry point so the dev worker
   exercises the whole pipeline on every seed — the place a broken handler or a
   drifted payload first surfaces.

   Idempotent for the direct-seeded part — truncates the seeded tables first, so
   re-running against an already-populated dev db is safe. Lives in `infra/src/`
   so it stays out of the prod uberjar."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [next.jdbc.transaction]
            [reader.domain.authors :as authors]
            [reader.domain.authorships :as authorships]
            [reader.db.crud :as crud]
            [reader.inbound :as inbound]
            [reader.ingest :as ingest]
            [reader.jobs :as jobs]
            [reader.papers :as papers]))

(def ^:private seeded-tables
  ["authors" "affiliations" "author_affiliations" "newsletter_sources"
   "articles" "papers" "newsletter_issues" "authorships" "users"
   "email_inboxes" "queue_items" "jobs"])

(def ^:private local-hosts #{"localhost" "127.0.0.1" "::1" "[::1]"})

;; Body HTML for the direct-seeded library. These stand in for readables already
;; extracted: real bodies are normally produced by the fetch/extract jobs and
;; sanitized at the ingest boundary, but seeding them directly gives the UI
;; instant content without waiting on (or depending on) the network. The
;; freshly-ingested items that DO exercise the jobs are set up by seed-live-jobs!
;; below. Articles/newsletters open with a `.lead` paragraph (the reader draws a
;; drop cap on it); the papers carry MathML so equation reflow is exercised.
;; Attributes use single quotes to keep these Clojure string literals clean.

(def ^:private white-album-html
  (str "<p class='lead'>For a season I kept a list of the things I would need in case the center did not hold: a quart of bourbon, two decks of cards, a transistor radio that took batteries the stores had stopped stocking.</p>"
       "<p>The summer ran long that year. We rented a house on a curve of the coast where the fog came in by four o'clock and stayed, and I learned to recognize the particular silence that precedes a phone call you do not want to take.</p>"
       "<h2>An attack of vertigo and nausea</h2>"
       "<p>The doctors had a vocabulary for it and I had another, and somewhere between the two the days went by. I made notes. I have the notes still, and reading them now I cannot always tell which sentences were observation and which were symptom.</p>"
       "<blockquote><p>The princess is caged in the consulate. The man with the candy will lead the children into the sea.</p></blockquote>"
       "<p>This is not a story about that summer so much as a record of what it felt like to assemble one and find the pieces would not stay where I set them.</p>"))

(def ^:private slouching-html
  (str "<p class='lead'>The center was not holding in the cold late spring of 1967, and the children were gathering where the rents were cheapest and the light came down at a slant through the eucalyptus.</p>"
       "<p>I went looking for a girl named Susan who was said to be living on the Panhandle, and instead I found a kitchen full of people none of whom would give a last name, passing a single orange between them as though it were a sacrament.</p>"
       "<h2>What it was like to be there</h2>"
       "<p>They had come from everywhere and from nowhere in particular, and they spoke a dialect assembled out of song lyrics and half-remembered lectures. To ask them a direct question was to watch the question dissolve.</p>"
       "<p>I stayed three weeks. By the end I had filled two notebooks and understood, if anything, less than when I arrived.</p>"))

(def ^:private marvin-html
  (str "<p class='lead'>Go. I roll the dice — a six and a two — and move the flatiron to St. Charles Place, which I buy for one hundred and forty dollars.</p>"
       "<p>My opponent and I have been playing for the better part of an afternoon, and the board between us has begun to feel less like a diagram than like a city we are both responsible for.</p>"
       "<h2>The real and the printed streets</h2>"
       "<p>Outside the window the actual Atlantic City runs down to the actual sea. The streets named on the board are out there too, most of them — though Marvin Gardens, the one everyone remembers, is not in Atlantic City at all, and never was.</p>"
       "<blockquote><p>It is a suburb within a suburb, secure behind a wrought-iron fence, and it is misspelled.</p></blockquote>"
       "<p>I land on Boardwalk, which my opponent owns, and on which he has built a hotel. I count out the rent. The afternoon, and the city, go on without me.</p>"))

(def ^:private attention-html
  (str "<h2>Introduction</h2>"
       "<p>Recurrent models factor computation along the symbol positions of the input and output sequences, precluding parallelization within training examples. We propose the Transformer, an architecture that dispenses with recurrence entirely and relies instead on an attention mechanism to draw global dependencies between input and output.</p>"
       "<h2>Scaled Dot-Product Attention</h2>"
       "<p>The input consists of queries and keys of dimension <math><msub><mi>d</mi><mi>k</mi></msub></math> and values of dimension <math><msub><mi>d</mi><mi>v</mi></msub></math>. We compute the dot products of the query with all keys, divide each by <math><msqrt><msub><mi>d</mi><mi>k</mi></msub></msqrt></math>, and apply a softmax to obtain the weights on the values:</p>"
       "<math display='block'><mrow><mi>Attention</mi><mo>(</mo><mi>Q</mi><mo>,</mo><mi>K</mi><mo>,</mo><mi>V</mi><mo>)</mo><mo>=</mo><mi>softmax</mi><mo>(</mo><mfrac><mrow><mi>Q</mi><msup><mi>K</mi><mi>T</mi></msup></mrow><msqrt><msub><mi>d</mi><mi>k</mi></msub></msqrt></mfrac><mo>)</mo><mi>V</mi></mrow></math>"
       "<p>Additive attention and dot-product attention are the two common variants; ours is the latter, scaled by the factor above to keep the softmax in a region with usable gradients.</p>"))

(def ^:private resnet-html
  (str "<h2>Introduction</h2>"
       "<p>Deeper neural networks are more difficult to train. We present a residual learning framework to ease the training of networks substantially deeper than those used previously, reformulating the layers as learning residual functions with reference to the layer inputs rather than unreferenced functions.</p>"
       "<h2>Residual Learning</h2>"
       "<p>Consider <math><mrow><mi>H</mi><mo>(</mo><mi>x</mi><mo>)</mo></mrow></math> as an underlying mapping to be fit by a few stacked layers, with <math><mi>x</mi></math> the input to the first of them. Rather than hope the layers approximate that mapping directly, we let them approximate a residual function, recasting the original mapping as:</p>"
       "<math display='block'><mrow><mi>H</mi><mo>(</mo><mi>x</mi><mo>)</mo><mo>=</mo><mi>F</mi><mo>(</mo><mi>x</mi><mo>,</mo><mo>{</mo><msub><mi>W</mi><mi>i</mi></msub><mo>}</mo><mo>)</mo><mo>+</mo><mi>x</mi></mrow></math>"
       "<p>We hypothesize that it is easier to optimize this residual mapping than the original, and that in the limit a deeper model should be no worse than its shallower counterpart.</p>"))

(def ^:private act-issue-html
  (str "<p class='lead'>A grab bag of links, half-formed theories, and one genuinely good chart. As always, reply if you think I'm wrong — you usually are, and the corrections are the best part of my week.</p>"
       "<h2>Links worth your time</h2>"
       "<ul>"
       "<li><a href='https://example.com/forecasting'>Why expert forecasts converge right before they fail</a> — better than its title.</li>"
       "<li><a href='https://example.com/cities'>Notes on why some cities feel alive at street level and others feel embalmed.</a></li>"
       "<li><a href='https://example.com/sleep'>The sleep-study replication everyone is arguing about,</a> with the caveats put back in.</li>"
       "</ul>"
       "<h2>One chart</h2>"
       "<p>If the trend holds, the line keeps going up and to the right, which is either very good or very bad depending on what you believe the y-axis measures. I lean optimistic. I usually do.</p>"))

;; Inputs for the live jobs (seed-live-jobs!). The external targets are chosen to
;; be stable, paywall- and JS-free, and OpenAlex-indexed, so the smoke test runs
;; reliably on every seed; they're easy to swap if one ever rots.

(def ^:private test-inbox-alias "test+1@inbox.reader.test")

(def ^:private live-article-url
  ;; Paul Graham's essays are static HTML, stable for years — a dependable target
  ;; for the :extract-article fetch+extract smoke test.
  "https://paulgraham.com/greatwork.html")

(def ^:private live-paper-ref
  ;; A real, OpenAlex-indexed arXiv paper (BERT), distinct from the direct-seeded
  ;; papers so it gets its own live :extract-paper fetch rather than being skipped
  ;; as already-extracted.
  {:kind :arxiv :id "1810.04805"})

(def ^:private sample-newsletter-eml
  "A minimal RFC822 newsletter for the :ingest-email smoke test. inbound/deliver!
   stores these bytes and enqueues the same job the prod webhook does; the worker
   parses the .eml and records an issue on the test user's queue, exercising the
   whole inbound path (storage read + MIME parse + sanitize + queue)."
  (str/join "\r\n"
            ["From: Astral Codex Ten <newsletter@astralcodexten.test>"
             (str "To: " test-inbox-alias)
             "Subject: ACT: a fresh batch of links"
             "Date: Thu, 19 Jun 2026 09:00:00 -0700"
             "Message-ID: <seed-act-2026-w25@astralcodexten.test>"
             "List-Unsubscribe: <https://astralcodexten.test/unsubscribe>"
             "MIME-Version: 1.0"
             "Content-Type: text/html; charset=UTF-8"
             ""
             (str "<h1>Links for the week</h1>"
                  "<p>Delivered straight to the reader inbox and parsed from a real .eml, "
                  "so the inbound pipeline gets exercised on every dev seed.</p>"
                  "<ul><li><a href='https://example.com/a'>Something worth reading</a></li>"
                  "<li><a href='https://example.com/b'>And another</a></li></ul>")]))

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
        ;; via the heuristic in reader.domain.authors/create!.
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
        ;; An institution (the academic-affiliation sense) — distinct from a source
        ;; you read from, so it's excluded from the sources index and only reached
        ;; from an author's "Affiliated with". The papers/OpenAlex path creates
        ;; these in prod; seeded here so the author page shows both relationships.
        google  (crud/create! tx :affiliations {:name "Google"
                                                :slug "google"
                                                :type "institution"
                                                :url  "https://research.google"})

        ;; Readables: three articles, two papers, one newsletter issue.
        white     (crud/create! tx :articles
                                {:affiliation-id    (:affiliations/id ny)
                                 :title             "The White Album"
                                 :slug              "the-white-album"
                                 :canonical-url     "https://www.newyorker.com/the-white-album"
                                 :word-count        5200
                                 :reading-time-secs 1560
                                 :abstract          "On living in California in the late sixties."
                                 :body-html         white-album-html})
        slouching (crud/create! tx :articles
                                {:affiliation-id    (:affiliations/id ny)
                                 :title             "Slouching Towards Bethlehem"
                                 :slug              "slouching-towards-bethlehem"
                                 :canonical-url     "https://www.newyorker.com/slouching-towards-bethlehem"
                                 :word-count        7100
                                 :reading-time-secs 2130
                                 :abstract          "A portrait of the Haight-Ashbury in 1967."
                                 :body-html         slouching-html})
        marvin    (crud/create! tx :articles
                                {:affiliation-id    (:affiliations/id ny)
                                 :title             "The Search for Marvin Gardens"
                                 :slug              "the-search-for-marvin-gardens"
                                 :canonical-url     "https://www.newyorker.com/the-search-for-marvin-gardens"
                                 :word-count        6400
                                 :reading-time-secs 1920
                                 :abstract          "Monopoly, Atlantic City, and the board behind the game."
                                 :body-html         marvin-html})

        attention (crud/create! tx :papers
                                {:affiliation-id (:affiliations/id arxiv)
                                 :title          "Attention Is All You Need"
                                 :doi            "10.48550/arXiv.1706.03762"
                                 :arxiv-id       "1706.03762"
                                 :abstract       "The Transformer architecture."
                                 :pdf-object-key "papers/1706.03762.pdf"
                                 :body-html      attention-html})
        resnet    (crud/create! tx :papers
                                {:affiliation-id (:affiliations/id arxiv)
                                 :title          "Deep Residual Learning for Image Recognition"
                                 :doi            "10.48550/arXiv.1512.03385"
                                 :arxiv-id       "1512.03385"
                                 :abstract       "Residual connections for very deep networks."
                                 :pdf-object-key "papers/1512.03385.pdf"
                                 :body-html      resnet-html})

        issue     (crud/create! tx :newsletter-issues
                                {:affiliation-id       (:affiliations/id act-nl)
                                 :subject              "ACT links for the week"
                                 :body-html            act-issue-html
                                 :raw-email-object-key "issues/act-2026-W21.eml"})

        ;; Users. test@example.com is the dev login (the lone address allowlisted
        ;; in env/dev/resources/dev.edn). Multi-user on purpose: the two queues
        ;; overlap (below).
        test-user (crud/create! tx :users {:email "test@example.com"  :display-name "Test User"})
        marcus    (crud/create! tx :users {:email "marcus@reader.test" :display-name "Marcus Chen"})]

    ;; Institutional affiliation (author_affiliations now holds only institutions —
    ;; the academic sense). Where these authors have *published* is derived from
    ;; their works, not stored here.
    (crud/create! tx :author-affiliations {:author-id      (:authors/id vaswani)
                                           :affiliation-id (:affiliations/id google)
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
    (crud/create! tx :email-inboxes {:user-id (:users/id test-user) :alias test-inbox-alias})
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

    ;; Durable jobs for the admin dashboard. The library papers above carry their
    ;; bodies already, so each one's :extract-paper job is recorded as `done` with
    ;; the real payload shape — the worker only polls `pending`, so these never
    ;; re-run. (The live :extract-paper job that actually hits the network is set
    ;; up in seed-live-jobs!.) A lone pending thumbnails job (no dev handler)
    ;; stands in for work still queued.
    (jobs/enqueue! tx "thumbnails" {:article-id (str (:articles/id white))})
    (crud/create! tx :jobs {:queue-name "extract-paper"
                            :payload    {:paper-id (str (:papers/id attention)) :kind "arxiv" :id "1706.03762"}
                            :state      "done"
                            :attempts   1})
    (crud/create! tx :jobs {:queue-name "extract-paper"
                            :payload    {:paper-id (str (:papers/id resnet)) :kind "arxiv" :id "1512.03385"}
                            :state      "done"
                            :attempts   1})

    ;; Returned so seed! can target the live jobs at the dev login's queue + inbox.
    test-user))

(defn- seed-live-jobs!
  "Drive one real job of each type through its production entry point, so the dev
   worker exercises the whole pipeline on every seed — where a broken handler or a
   drifted payload first surfaces (a malformed enqueue used to fail only in the
   worker log). Unlike the direct-seeded library, these create fresh placeholders
   the worker fills over the network (paper, article) or an inbound issue it
   records from a parsed .eml (email). Enqueue-only: the jobs run when a worker is
   present (dev/prod) and simply sit pending under tests. Runs after the library
   commits, against the real datasource, so the worker sees fully-seeded data."
  [ds store user-id]
  (papers/start! ds user-id live-paper-ref)
  (ingest/start! ds user-id live-article-url)
  (inbound/deliver! ds store test-inbox-alias (.getBytes ^String sample-newsletter-eml "UTF-8")))

(defn seed!
  "Populate the dev database: a direct-seeded library of already-extracted
   readables (instant content for the UI) plus one real job of each type for the
   worker to run (`seed-live-jobs!`). `store` backs the inbound-email job — the
   file store in dev, the in-memory one under tests."
  [ds store]
  (log/info "seed starting")
  (assert-local-db! ds)
  ;; One outer transaction so a partial failure rolls the whole library back.
  ;; `:ignore` makes the inner `with-transaction` in `authorships/attach!` join us
  ;; instead of throwing on a nested tx.
  (let [test-user (binding [next.jdbc.transaction/*nested-tx* :ignore]
                    (jdbc/with-transaction [tx ds]
                      (seed-in-tx! tx)))]
    (seed-live-jobs! ds store (:users/id test-user)))
  (log/info "seed done"))
