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

## Step 3 — Authentication ✅

Hanko Cloud handles login. The app verifies the session JWT against
Hanko's JWKS and maps the subject/email to a `users` row, provisioning
only invited addresses.

Reality check vs. the original sketch: Hanko has no OAuth-style
`/auth/callback`. Login is driven by Hanko's frontend element, which
sets an HttpOnly `hanko` cookie (a signed JWT) client-side; the backend
just verifies that cookie. So there is no magic-link form and no server
callback route.

**Components**
- `reader.auth` — verifies the `hanko` JWT against the JWKS (via
  `clj-jwt`, which fetches/caches the key set and handles rotation),
  plus pure claim→attrs and allowlist helpers.
- `reader.auth.middleware` — default-deny gate: `:public?` routes pass;
  otherwise verify the cookie, load/provision the invited user, attach
  `:user`/`:user-id`, redirect a browser GET to `/login` (401 for other
  methods, 403 for a valid-but-uninvited identity).
- `reader.users` — find-or-provision from a verified identity.
- `reader.web.csrf` — Origin/Referer check on unsafe methods, since the
  cookie session introduces a CSRF surface.
- `reader.ui.pages.login` + `reader.handlers.auth` — the `<hanko-auth>`
  element island and the `/login` / `/logout` routes.

**Done when** *(met)*
- Signing in via the Hanko element establishes a session; logout clears
  the cookie.
- Protected routes (everything except `/login`, `/logout`, `/health`,
  `/static/*`) require a valid session, and only allowlisted emails are
  provisioned.
- Tests cover JWT verification (valid / expired / tampered / malformed),
  the allowlist gate, and the redirect / 401 / 403 paths.

Invite allowlist is config: a set in dev, `#env/set "ALLOWED_EMAILS"`
(a Fly secret) in prod. It gates *provisioning* only — adding an address
lets that person sign in, but removing one does **not** revoke a user
who has already signed in (their `users` row stands); revoke by deleting
the row. Manual setup still needed: create the Hanko Cloud project and
fill the `CHANGEME` URLs in `dev.edn` (and the prod secrets).

---

## Step 4 — Reading queue (read side) ✅

Users can see what's in their queue, open and read items, mark them
read/unread, and archive them.

**Reality check vs. the original sketch**
- The queue renders on `GET /` — the home page *is* the reading list —
  not a separate `GET /queue`. There's no `reader.ui.pages.queue`;
  `reader.ui.pages.home` renders it.
- Removal is a soft **archive** (`POST /queue/:id/archive` flips the
  row's `state` to `archived`), not a hard delete. The earlier thin UI
  deleted the underlying readable via `/readables/:table/:id/delete`;
  that route and handler are gone. Re-adding an archived readable
  reactivates the same row (`enqueue!` upserts) rather than tripping the
  per-user unique constraint.
- State changes are a plain `POST` → `303` redirect (full server
  render), not an HTMX in-place swap. HTMX for mark-read is deferred
  until the reader view lands.
- `reader.reading/enqueue!` already exists and `POST /articles`
  enqueues a hand-added article in the **same transaction** as the
  insert — a manual-ingest primitive that arrives ahead of Step 5
  (no async job or extraction yet).

**Delivered**
- `reader.reading` — per-user queue assembly (scoped reads via
  `reader.readables/catalog-of`, so a render touches only the user's
  queued readables, not the whole library), `enqueue!` (upsert), and
  owner-scoped `archive!`.
- `reader.handlers.queue` — `POST /queue/:id/archive`, owner-scoped (a
  forged or missing id answers 404).
- Home renders the signed-in user's active queue, newest-first, with a
  per-item archive control and a queue-state label.
- `reader.db.crud` gains `find-in` (scoped `IN` reads) and `upsert!`.
- Tests across the HTTP stack, the queue domain (enqueue / archive /
  re-add / cross-user isolation), and the pure catalog assembly. Seed
  now provisions two users whose queues overlap on shared readables.

**Delivered (since)**
- `GET /queue/:id` (dispatched by readable type) + `reader.ui.pages.reader`
  — renders an article/newsletter body and a paper's external links.
  First open promotes the item to `reading` and stamps `started_at`.
- `POST /queue/:id/read` and `/unread` for state changes, owner-scoped.

---

## Step 5 — Manual ingest ✅

Users add an article by pasting a URL; it's fetched, extracted, and
queued asynchronously.

**Delivered**
- `POST /readables` — upserts a placeholder article keyed on
  (canonical url, fetched-on), enqueues it on the queue, and enqueues an
  `:extract-article` job; an HTMX row polls `/queue/:id/row` until done.
- `reader.ingest` + `reader.ingest.{fetch,extract,entities,events}` —
  jsoup + Readability4J body extraction, with a swappable entity abstraction
  (deterministic metadata extractor now, LLM-backed later) behind the
  EntityResult contract.
- `reader.jobs.worker` — Integrant-owned core.async loop draining the
  jobs table via `claim-next!` (`FOR UPDATE SKIP LOCKED`), with
  exponential-backoff retries and a `failed` terminal state.
- `/admin/extractions` — an eval dashboard over `extraction_events`
  (coverage, latency, errors, recovery) — the instrument that tells us
  when to turn on the LLM entity tier.

---

## Step 6 — Inbound email 🚧

Newsletters arrive by email and end up in the queue automatically.

**Design (decided).** Cloudflare Email Routing → a thin Email Worker that
PUTs the raw `.eml` to R2 (native binding) and POSTs signed metadata to the
app → an `:ingest-email` job that parses and files it. Chosen over an
inbound-parse provider (SendGrid/Mailgun/SES) because the Cloudflare path is
flat-cost — Email Routing free, R2 zero-egress, Workers a free tier — rather
than per-email metered, and the raw mail stays in our own R2. The Worker is
the integration point because Email Routing's only programmatic hook is a
Worker (it can otherwise only forward to an address); the app can't be the MX
itself (ADR 0004).

**Server side — delivered (Slices 0–4, behind a stubbed R2 + simulated
worker, no domain needed):**
- `reader.storage` — a `Blobs` abstraction (`:reader.storage/store`), in-memory
  stub in dev/test, R2 in prod. Mirrors embedded-postgres-for-Neon.
- Per-user `email_inboxes` alias — a friendly haikunator name plus a random
  token (`aged-morning-k3f9x2@<domain>`), provisioned idempotently (with
  collision-retry on the unique index) and surfaced on `/settings`.
- `POST /api/inbound` — public but HMAC-signed (`reader.web.signature`):
  constant-time verify over `timestamp + body`, 5-minute replay window,
  Malli-validated payload, resolves the alias to a user, enqueues
  `:ingest-email`. Fails closed when the shared secret is unset.
- `reader.ingest/ingest-email!` + `reader.ingest.email` +
  `reader.newsletters` — fetch the `.eml`, parse it (Jakarta Mail:
  subject, sender, sent-at, Message-ID, jsoup-sanitized body), resolve or
  create the newsletter source by sender domain and the author from the
  From line, write `newsletter_issues` + `authorships` + `queue_items` in
  one transaction. Idempotent on Message-ID.
- The reader view renders the sanitized newsletter body.

**Still pending — Slice 5 (needs external setup):**
- `worker/` — the Email Worker + `wrangler.toml`.
- The real `:r2` storage backend + prod profile wiring.
- A registered domain on Cloudflare, MX records, an Email Routing rule,
  and the `INBOUND_HMAC_SECRET` / R2 / domain secrets.

**Done when**
- Sending a real email to an alias results in a newsletter issue
  appearing in the user's queue within a few seconds.
- The signature check rejects unsigned and replay-attempted requests. ✅
- Unknown senders create the right affiliation as a side effect. ✅

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
