# Reader

A personal reading queue for articles, papers, and newsletter issues.
Each user gets an inbound email alias for newsletters, can save URLs
and upload PDFs, and works through a single ordered queue.

The repo is currently at v0.1 — an end-to-end deployable scaffold
(http-kit + Reitit, Integrant-managed lifecycle, vanilla CSS, CI to
Fly.io) with the domain features still to come. See
[`docs/roadmap.md`](docs/roadmap.md) for what's next.

## Stack

- **Server**: Clojure with http-kit, Reitit + Malli, Hiccup
- **Frontend**: server-rendered HTML, HTMX + Alpine, vanilla CSS
- **Storage**: Postgres (Neon in prod), Cloudflare R2 for blobs
- **Auth**: Hanko (magic links + passkeys)
- **Infra**: Fly.io, GitHub Actions, Babashka tasks

## Prerequisites

- [Clojure CLI](https://clojure.org/guides/install_clojure) (Java 25)
- [Babashka](https://babashka.org/)

No Docker, no local Postgres install. The dev profile lifecycles an
[embedded-postgres](https://github.com/zonkyio/embedded-postgres) under
Integrant — same in tests. A real Postgres (Neon) ships only in prod.

## Run it locally

```sh
bb dev
```

Brings up the dev Integrant system: the embedded Postgres, the
HikariCP-pooled datasource, the Migratus migrator (applies every
pending migration on start), http-kit on `:3000`, and a cider-nrepl
server on an OS-assigned port. That port is advertised in `.nrepl-port`
so editors and other tools can discover and connect to the running JVM
— nothing hardcodes a port.

`bb dev` cooperates with a REPL you've already started instead of
fighting it (see [Editor & REPL](#editor--repl)).

In another terminal, populate it with realistic fixtures:

```sh
bb db:seed
```

See [Database](#database) for what that loads, how migrations apply,
and how to set up Neon for prod.

### Signing in

Every route except `/login`, `/health`, and `/static/*` requires a
signed-in user. Auth is [Hanko](https://hanko.io) — passwordless
(passkeys, plus an emailed passcode), so there is nothing
password-shaped to seed.

The dev profile points at a shared Hanko Cloud project and invite-gates
provisioning to a single throwaway address, `test@example.com`
(`:allowed-emails` in
[`env/dev/resources/dev.edn`](env/dev/resources/dev.edn)). `bb db:seed`
also provisions `test@example.com` as one of its two seeded users, with
a pre-populated queue — so once you sign in you land on real data, not
an empty list. To sign in:

1. In the [Hanko dashboard](https://cloud.hanko.io) for the dev
   project, create a user for `test@example.com`. You can't use the
   `/login` sign-up flow for it: that sends a confirmation code to the
   address, and `example.com` can't receive mail — so add the user
   directly in the dashboard instead.
2. Visit <http://localhost:3000/login> and authenticate as
   `test@example.com`.

You inherit that user's seeded queue (articles, a paper, a newsletter
issue — two of them also in a second seeded user's queue at different
read states, exercising the shared-readable model). Order doesn't
matter: sign in whenever, and a refresh after `bb db:seed` picks up the
seeded queue. Invite more testers by adding their addresses to
`:allowed-emails`.

Other useful tasks (run `bb tasks` for the full list):

| Task           | What it does                                          |
| -------------- | ----------------------------------------------------- |
| `bb dev`       | run the dev system (reuses a running REPL if one is up) |
| `bb db:seed`   | populate the running dev db with realistic fixtures   |
| `bb lint`      | clj-kondo over `src`, `test`, `env`                   |
| `bb fmt`       | cljfmt check                                          |
| `bb fmt:fix`   | cljfmt fix                                            |
| `bb ci`        | lint + fmt-check + tests (what CI runs)               |
| `bb build`     | build the production uberjar                          |
| `bb image`     | build a `reader:latest` container image               |

## Editor & REPL

In Clojure the REPL is the place to do everything, so the dev system is
designed to live in one running JVM that both your editor and the bb
tasks attach to. Discovery goes through the conventional `.nrepl-port`
file: whoever starts the nREPL writes it, everyone else reads it. That
means the two ways interact in either order:

- **`bb dev` first, editor second.** `bb dev` boots the system and
  starts a cider-nrepl server, writing its (OS-assigned) port to
  `.nrepl-port`. In your editor, **connect to a running REPL** (Calva:
  "Connect to a Running REPL"; CIDER: `cider-connect-clj`) — it reads
  `.nrepl-port` and attaches. cider-nrepl is on the server's classpath,
  so you get the full editor experience over the connection, not a
  bare socket.

- **Editor first, `bb dev` second.** Jack in from your editor **with
  the `:dev` alias** (it carries `integrant.repl`, embedded-postgres,
  and the `dev` ns). Then `bb dev` notices the advertised `.nrepl-port`,
  and instead of starting a competing JVM it boots the system *inside
  your editor's REPL* over nREPL and exits — no second http server, no
  second embedded Postgres. Re-running `bb dev` when the system is
  already up is a no-op.

Either way, `bb db:seed` attaches to the same `.nrepl-port`, so it
seeds whichever system is live.

Two caveats worth knowing:

- There is a single `.nrepl-port` file, so if you run `bb dev` **and**
  an editor REPL at once, the last one to start owns the file. In
  practice you run one or the other.
- The editor-first path needs the `:dev` alias on the REPL's classpath.
  Jack in without it and `bb dev` will tell you so rather than failing
  obscurely.

## Database

Reader runs on Postgres in every environment. Locally there is nothing
to install — dev and tests lifecycle an embedded Postgres under
Integrant. Prod points at a [Neon](https://neon.tech) database.

The schema is owned by the migrations in
[`resources/migrations/`](resources/migrations/). The
`:reader.db/migrator` component applies every pending migration on
startup, so a freshly created database is brought fully up to date the
first time the system boots — there is no separate "run migrations"
step in any environment.

### Dev and test (embedded)

`bb dev` starts an embedded Postgres on an ephemeral port, opens a
HikariCP pool against it, and applies migrations — no Docker, no local
Postgres, no setup. Tests do the same per system against a throwaway
database. First run unpacks the Postgres binary into
`~/.embedded-postgres-binaries` (~1.5s); later runs are warm.

Populate the running dev database with fixtures:

```sh
bb db:seed
```

This truncates the seeded tables and reinserts a coherent set —
authors, affiliations, articles, papers, a newsletter issue,
authorships, two users whose reading queues overlap on shared
readables, and a couple of jobs. It is
idempotent and runs over nREPL into the system already running: it
finds the server through `.nrepl-port` and evals against
`integrant.repl.state/system`, so the seed lands in the database the
dev server is serving, with no second JVM — whether that system is one
`bb dev` started or one in your editor REPL.

To inspect the dev database directly, grab its JDBC URL from the
running system — the port is ephemeral, so it changes each run. Either
read it from the `bb dev` logs:

```
reader.dev.infra.postgres ::started :port <PORT>
```

```sh
psql "postgresql://postgres:postgres@localhost:<PORT>/postgres"
```

or pull it from a connected REPL:

```clojure
(:jdbc-url (:reader.dev.infra/postgres integrant.repl.state/system))
```

### Production (Neon)

Prod connects to Neon through a single `DATABASE_URL` — the full JDBC
URL, credentials and SSL mode included. To build it:

1. Create a [Neon](https://neon.tech) project near `yyz` (where the app
   runs). Neon provisions a database and role.
2. Take the dashboard connection string and put it in JDBC form: prefix
   `jdbc:` and keep `?sslmode=require` (Neon requires TLS).

   ```
   jdbc:postgresql://<user>:<password>@<host>.neon.tech/<db>?sslmode=require
   ```

Set it as a Fly secret with the rest in [One-time production
setup](#one-time-production-setup) — never commit it. On the next deploy
`:reader.db/datasource` pools against Neon and `:reader.db/migrator`
applies pending migrations before traffic is served; the system refuses
to start without `DATABASE_URL` rather than coming up half-wired.

## Deployment

Production runs on a single Fly.io machine in `yyz`, fronted by
Fly's HTTPS edge, with Neon for Postgres and Cloudflare R2 for blob
storage. The unit of deployment is a container image built from the
multi-stage [`Dockerfile`](Dockerfile), which produces an
`eclipse-temurin:25-jre` image with the uberjar at `/app/reader.jar`
and the prod profile at `/app/conf/prod.edn`. The image splits
`ENTRYPOINT ["java", "-cp", "/app/conf:/app/reader.jar"]` (the JVM prefix)
from `CMD ["reader.main", "prod.edn"]` (the app server) — so Fly's
`release_command` (run as ENTRYPOINT + command) cleanly becomes
`java … reader.migrate prod.edn` rather than extra args to `reader.main`.

### Automatic (the normal path)

Every push to `main` runs [`.github/workflows/ci.yml`](.github/workflows/ci.yml):

1. `bb ci` — lint, format check, tests
2. On green: `flyctl deploy --remote-only`

Fly's `min_machines_running = 0` means the machine stops when idle
and auto-starts on the first request. Health checks hit `/health`
every 30s while running.

### Manual

To manually deploy the app:

```sh
bb deploy   # flyctl deploy --remote-only
```

`bb deploy` depends on `flyctl` being installed, logged in, and the
Fly app already existing. If any of those are missing the task runner
should give you an actionable hint to help get unstuck.

### One-time production setup

A fresh production deployment needs the Fly app created, its backing
services provisioned, its runtime secrets set, and a deploy token wired
into CI. Do these once.

**1. Create the Fly app.** App names are globally unique across the
entire platform, so the default `reader` is almost certainly taken.
Edit `fly.toml`'s `app = "..."` to something namespaced to you (e.g.
`kirahowe-reader`), then:

```sh
flyctl auth login   # if not already
bb fly:init         # create the Fly app named in fly.toml
```

**2. Provision the backing services.** Create the Neon database (see
[Production (Neon)](#production-neon) for the connection string) and a
production [Hanko Cloud](https://cloud.hanko.io) project. Set the
project's **app URL** to your public origin: passkeys are WebAuthn and
origin-bound, so a project pointed at `localhost` won't authenticate on
the deployed domain. Its API URL (e.g. `https://<id>.hanko.io`) is the
`HANKO_API_URL` the app needs.

**3. Set the runtime secrets.** The prod profile reads four values from
the environment — every `#env` literal in
[`env/prod/resources/prod.edn`](env/prod/resources/prod.edn). `PORT`
comes from `fly.toml`; the rest are secrets, set in one shot (a single
`secrets set` triggers a single redeploy):

```sh
flyctl secrets set \
  DATABASE_URL="jdbc:postgresql://<user>:<pass>@<host>.neon.tech/<db>?sslmode=require" \
  SITE_ORIGIN="https://kirahowe-reader.fly.dev" \
  HANKO_API_URL="https://<id>.hanko.io" \
  ALLOWED_EMAILS="you@example.com,friend@example.com"
```

| Secret | What it is |
| ------ | ---------- |
| `DATABASE_URL`   | Full Neon JDBC URL, credentials + `?sslmode=require` (see [Production (Neon)](#production-neon)) |
| `SITE_ORIGIN`    | Public origin of the app; the CSRF check compares request origins against it |
| `HANKO_API_URL`  | Hanko project base URL; the sign-in page's `<hanko-auth>` element talks to it, and the auth middleware derives the JWKS URL (`<api-url>/.well-known/jwks.json`) from it to verify session JWTs |
| `ALLOWED_EMAILS` | Comma-separated invite allowlist gating *provisioning* only. Optional — unset provisions no one; add testers later with a plain `secrets set`, no redeploy of code |

All but `ALLOWED_EMAILS` are required: the system refuses to start
without them rather than coming up half-wired. Verify with `flyctl
secrets list` (it shows names and digests, never values).

**4. Authorize CI to deploy.** The `deploy` job in
[`.github/workflows/ci.yml`](.github/workflows/ci.yml) authenticates to
Fly with a `FLY_API_TOKEN` GitHub Actions secret. Without it the deploy
fails with `Error: no access token available`. Mint a deploy token
scoped to the app and store it on the repo:

```sh
fly tokens create deploy -a kirahowe-reader -x 999999h \
  | gh secret set FLY_API_TOKEN --repo <owner>/<repo>
```

Piping straight into `gh` keeps the token out of your shell history and
terminal scrollback; confirm it landed with `gh secret list`.

With all four done, deploy — `bb deploy`, or push to `main` and let CI
do it — and watch the rollout with `flyctl logs`.

### Inbound email (newsletters)

Newsletters reach a user's queue by being emailed to their alias (a friendly
but unguessable name like `aged-morning-k3f9x2@<your-domain>`, shown on
`/settings`). The path is: Cloudflare
Email Routing → an Email Worker that writes the raw `.eml` to R2 and POSTs a
signed notification to `POST /api/inbound` → the `:ingest-email` job parses
and files it. The app can't be an MX itself, so this bridge is required (see
[ADR 0004](docs/adr/0004-deployment-and-infrastructure.md)). The Worker lives
in [`worker/`](worker/).

The receiver is a per-environment Integrant abstraction (`:reader.handlers/inbound`):
prod runs `:impl :webhook` (the HMAC contract above); dev/test/PR tenants run
`:impl :direct`, which skips the worker/R2/HMAC entirely — POST a raw `.eml`
with the recipient as a query param and it runs the *same* downstream:

```sh
curl -X POST "http://localhost:3000/api/inbound?alias=<your-/settings-alias>" \
  -H 'content-type: message/rfc822' --data-binary @some-newsletter.eml
```

Dev stores the `.eml` on local disk (the `:file` backend under the configurable
`:reader.storage/file-root`, default `/tmp`), so no R2 is involved. The
`:direct` endpoint is open on localhost; set its `:token` for a reachable PR
tenant. The rest of this section is the **production** wiring:

**1. A domain on Cloudflare.** Register (or transfer) a domain so its DNS is on
Cloudflare — this is the host part of every alias. The app is also served from
it, so add the custom domain to Fly and point Hanko at it:

```sh
flyctl certs add themiscellany.app          # provisions a cert; follow the
                                            # printed DNS record to add in Cloudflare
flyctl secrets set SITE_ORIGIN="https://themiscellany.app"
```

Then set the Hanko project's **app URL** to `https://themiscellany.app`
(passkeys are origin-bound, so this must match), and set the Worker's
`READER_API_URL` to the same (step 4).

**2. An R2 bucket + S3 token.** Create a bucket (e.g. `miscellany-inbound`) and
an R2 API token (an S3 access key id + secret) scoped to it. Note the account
id too.

**3. Enable Email Routing** on the domain (adds the MX + SPF records). Leave the
catch-all rule unset until the Worker is deployed (step 5).

**4. Deploy the Worker.** Edit [`worker/wrangler.toml`](worker/wrangler.toml)
— set `bucket_name` and `READER_API_URL` (the app's public origin) — then:

```sh
cd worker
npx wrangler deploy
npx wrangler secret put INBOUND_HMAC_SECRET   # any strong random string
```

**5. Point the catch-all at the Worker.** Cloudflare → Email → Email Routing →
Routing rules → set the catch-all action to *Send to a Worker* → the deployed
worker.

**6. Set the app's Fly secrets** (the same `INBOUND_HMAC_SECRET` value as the
Worker's):

```sh
flyctl secrets set \
  INBOUND_HMAC_SECRET="<same value as the Wrangler secret>" \
  INBOUND_EMAIL_DOMAIN="themiscellany.app" \
  R2_ACCOUNT_ID="<cloudflare account id>" \
  R2_BUCKET="miscellany-inbound" \
  R2_ACCESS_KEY_ID="<r2 access key id>" \
  R2_SECRET_ACCESS_KEY="<r2 secret access key>"
```

To verify: grab your alias from `/settings`, email something to it, and watch
it appear in your queue (`flyctl logs` shows the `:ingest-email` job running).

### Configuration

Configuration is EDN, not env vars. `base-system.edn` is always
loaded; the profile passed on the command line is meta-merged on
top:

```
resources/base-system.edn          # defaults, always loaded
env/dev/resources/dev.edn          # bb dev → -m reader.dev.main
env/test/resources/test.edn        # bb test
env/prod/resources/prod.edn        # in the image at /app/conf, on the classpath
```

The few values that must come from the environment — the HTTP `PORT`,
the database connection string and auth config (`DATABASE_URL`,
`SITE_ORIGIN`, `HANKO_API_URL`, `ALLOWED_EMAILS`), and the inbound-email
config (`INBOUND_HMAC_SECRET`, `INBOUND_EMAIL_DOMAIN`, `R2_ACCOUNT_ID`,
`R2_BUCKET`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`) — are pulled in
inline via reader literals like `#env/long ["PORT" 8080]` and
`#env "DATABASE_URL"`. See [One-time production
setup](#one-time-production-setup) and [Inbound
email](#inbound-email-newsletters) for how those are supplied as Fly
secrets and a CI deploy token.

### Multiple environments

Every deployed environment runs the **same image and the same `prod.edn`** —
that profile reads all of its values from the environment, so an environment
*is* its set of secrets, not a separate config file. Adding one (say staging)
is therefore cheap and needs no code change:

1. `flyctl apps create <name>` (e.g. `themiscellany-staging`) and deploy the
   same image to it (`flyctl deploy -a <name>`, or a `fly.staging.toml`).
2. Set that app's own secrets (its own `DATABASE_URL`, `SITE_ORIGIN`, …).
3. For inbound email, give it a named worker environment in
   [`worker/wrangler.toml`](worker/wrangler.toml) (an `[env.staging]` block
   with its own `READER_API_URL` + bucket) and its own Email Routing domain.

Only `dev`, `test`, and `prod` exist today; the above is the recipe when a new
one is wanted.

## Useful docs

- [`docs/principles.md`](docs/principles.md) — how this codebase is built and why
- [`docs/architecture.md`](docs/architecture.md) — components and request lifecycles
- [`docs/data-model.md`](docs/data-model.md) — entities and relationships
- [`docs/roadmap.md`](docs/roadmap.md) — the path from scaffold to useful
- [`docs/adr/`](docs/adr/) — decision records

## License

MIT — see [LICENSE](LICENSE).
