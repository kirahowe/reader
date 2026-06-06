# Roadmap

The path from v0.1 (this scaffold) to a Reader that's actually useful
for reading. Build wide first — each step adds a thin slice across the
whole stack, deployable on its own — then circle back to deepen the
parts that warrant it.

Each step lists its **goal**, the **components** it touches, and a
**definition of done**. Nothing in here is sequenced more tightly than
needed; the order reflects dependencies, not commitments.

---

## Step 1 — Skeleton ✅

End-to-end "hello, world" that's deployable, with CI, container image,
Integrant lifecycle, vanilla CSS, and a passing test suite.

**Done.** This is what's on the branch today.

---

## Step 2 — Data layer ✅

Postgres becomes the source of truth for everything that's not a blob.

**Components**
- `:reader.db/datasource` — HikariCP-pooled `DataSource` via next.jdbc
- `:reader.db/migrator` — Migratus, applies every pending migration at
  startup
- `resources/migrations/` — SQL migration creating the v1 schema from
  [data-model.md](./data-model.md): `authors`, `affiliations`,
  `author_affiliations`, `newsletter_sources`, `articles`, `papers`,
  `newsletter_issues`, `authorships`, `users`, `email_inboxes`,
  `queue_items`, `jobs`.
- `reader.db.crud` — generic, data-driven CRUD over any table via
  HoneySQL (`by-id`, `find-many`, `find-1`, `create!`, `update!`,
  `delete!`). No protocols, no records, no ORM-flavored escape hatches.
  Tables that need real domain logic get their own namespace.
- `reader.db.types` — global next.jdbc extensions so Clojure maps and
  vectors marshal into Postgres `jsonb`, and `java.time.Instant` binds
  as `timestamptz`.
- `reader.authorships` — polymorphic FK validator for the
  readable→author bridge (postgres can't enforce it).
- `reader.jobs` — durable job queue with `enqueue!`, `claim-next!`
  (atomic via `SELECT … FOR UPDATE SKIP LOCKED`), `complete!`, `fail!`.
- `:reader.dev.infra/postgres` — embedded-postgres lifecycle, started
  by Integrant in dev and tests. No Docker, no `compose up` — same
  Postgres binary for both. Lives in `infra/src/` so it stays out of
  the prod uberjar.
- `bb db:seed` — populates the running dev database with realistic
  fixtures over nREPL, against the same JVM `bb dev` is using.

**Done.** Schema migrations apply cleanly, every CRUD-only table has
a round-trip integration test (real embedded Postgres), and `bb dev`
boots with the db wired and nREPL listening on `:7888`.

---

## Step 3 — Authentication

Hanko handles login. The application verifies JWTs and maps subjects
to `users` rows.

**Components**
- `:reader.auth/hanko-jwks` — fetched and cached JWK set
- `reader.http.middleware.auth` — verifies the cookie JWT, attaches
  `:user-id` to the request, redirects to `/login` if missing
- `reader.ui.pages.login` — magic-link form
- A small set of routes: `/login`, `/auth/callback`, `/logout`

**Done when**
- Logging in over magic link establishes a session; logout clears it.
- Protected routes (everything except `/`, `/login`, `/auth/callback`,
  `/health`, `/api/inbound`, `/static/*`) require a valid session.
- Tests cover JWT verification, the missing/expired/invalid paths, and
  a happy-path login round-trip.

---

## Step 4 — Reading queue (read side)

Users can see what's in their queue and open one item.

**Components**
- Routes: `GET /queue`, `GET /readable/:id` (dispatched by type),
  `POST /queue/:id/mark-read`, `POST /queue/:id/archive`
- `reader.reading.*` — domain functions for the queue and the reader
  view
- `reader.ui.pages.queue`, `reader.ui.pages.reader`
- HTMX swaps for state changes — no full page reload to mark something
  read.

**Done when**
- A queue with hand-seeded data renders correctly.
- Mark-read and archive flip the row and update the view in-place.
- A readable page renders the body for an article, a PDF object for a
  paper (just the link to R2 for now — viewer comes later), and the
  HTML body for a newsletter issue.

---

## Step 5 — Manual ingest

Users add an article by pasting a URL.

**Components**
- `POST /readables` — accepts a URL, enqueues an `:extract-article` job
- `reader.jobs.extract-article` — fetches the URL, extracts a
  reader-view body (Crux / Trafilatura / readability-clj), guesses the
  author and affiliation, writes the row
- Worker (Integrant-managed core.async loop) draining
  `jobs WHERE state = 'pending'` via `FOR UPDATE SKIP LOCKED`.

**Done when**
- Pasting a URL produces an `articles` row, an `authorships` row, and
  a `queue_items` row, asynchronously, with the extracted body visible
  in the reader view.
- The job table reflects success/failure with attempts, last error,
  and locked_until accounted for.

---

## Step 6 — Inbound email

Newsletters arrive by email and end up in the queue automatically.

**Components**
- A Cloudflare Worker (separate small repo or `worker/` subdir): runs
  inside Email Routing, writes the raw `.eml` to R2, signs and POSTs
  `{alias, r2-key, from, subject}` to `/api/inbound`.
- `:reader.storage/r2` — S3-SDK client (already needed by step 5 if
  PDFs are involved earlier, but lands here at the latest).
- `POST /api/inbound` — signature verification, Malli-validated body,
  enqueues `:ingest-email`.
- `reader.jobs.ingest-email` — fetches the `.eml` from R2, parses
  it, finds or creates the affiliation (matching by `newsletter_sources.inbound_email_alias`),
  finds or creates the author, writes a `newsletter_issues` row plus
  `authorships` plus `queue_items`.

**Done when**
- Sending a real email to an alias results in a newsletter issue
  appearing in the user's queue within a few seconds.
- The signature check rejects unsigned and replay-attempted requests.
- Unknown senders create the right affiliation as a side effect.

---

## Step 7 — PDFs and papers

Users upload a PDF; it lands in R2 and shows up as a paper.

**Components**
- `POST /papers` — multipart upload, signs an R2 PUT URL, returns it;
  client uploads directly to R2.
- `POST /papers/:id/finalize` — confirms the upload, enqueues
  `:extract-paper`.
- `reader.jobs.extract-paper` — pulls metadata from PDF (PDFBox or
  Apache Tika), populates `papers` fields (DOI, abstract, title where
  extractable).
- In-browser viewer: an embedded `<object>` or pdf.js island on the
  paper page.

**Done when**
- Uploading a PDF produces a `papers` row, a `pdf_object_key`, and an
  extracted abstract.
- The paper page renders the PDF inline.

---

## Step 8 — Jobs hardening

Make the worker safe to leave running.

**Components**
- Retry policy with exponential backoff and a `failed` terminal state
  after N attempts.
- A simple `GET /admin/jobs` view (auth-gated) showing recent failures.
- Metrics: jobs processed per minute, time-in-state, queue depth.
- A periodic reconciliation that re-enqueues jobs `locked_until` in
  the past (worker crash recovery).

**Done when**
- A worker process killed mid-job is recovered by the next worker
  without manual intervention.
- A poison-pill job ends in `failed` rather than spinning forever.
- The admin view shows real numbers driven by Telemere signals.

---

## Step 9 — Operational polish

The boring-but-important stuff before this is the daily-driver.

**Components**
- `GET /health` upgraded to actually ping the DB and check job-worker
  liveness; Fly health check switched to it.
- Telemere handler in prod ships JSON to a destination (Better Stack,
  Axiom, or just stdout for Fly's log pipeline — decide when we get
  there).
- Basic dashboards: request rate, error rate, p95 latency, queue depth.
- A `bb backup` task that snapshots Neon to R2 nightly.
- Deploy gates: CI must be green and tests must include each new
  domain.

**Done when**
- Production has been left alone for a week with no operational
  intervention.

---

## Beyond v1

These are deliberately out of scope for v1 but worth naming so they
don't accidentally creep in:

- **Tags.** Probably yes eventually; not in v1.
- **Multi-user public deployment.** The schema is multi-tenant-ready,
  but the auth flow and rate-limiting story aren't designed for it.
- **Search.** Postgres FTS is a small step; not yet.
- **Recommendations / author suggestions.** This is the use case that
  might one day justify pulling the graph into a graph store.
- **Mobile app.** The web app is mobile-friendly; a native app is a
  later question.
