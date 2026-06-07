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

Prod connects to Neon through a single `DATABASE_URL` environment
variable — the full JDBC URL, credentials and SSL mode included. One
time setup:

1. Create a Neon project (pick a region close to `yyz` to keep latency
   down). Neon provisions a database and a role for you.
2. Copy the connection string from the Neon dashboard and put it in
   JDBC form — prefix `jdbc:` and keep `?sslmode=require`, since Neon
   requires TLS:

   ```
   jdbc:postgresql://<user>:<password>@<host>.neon.tech/<db>?sslmode=require
   ```

3. Hand it to the app as a Fly secret (never commit it):

   ```sh
   flyctl secrets set DATABASE_URL="jdbc:postgresql://…?sslmode=require"
   ```

That is all the database wiring prod needs. On the next deploy the
machine boots, `:reader.db/datasource` opens a pool against Neon, and
`:reader.db/migrator` applies any pending migrations before the app
serves traffic. `DATABASE_URL` is required in prod — the system
refuses to start without it rather than coming up half-wired.

## Deployment

Production runs on a single Fly.io machine in `yyz`, fronted by
Fly's HTTPS edge, with Neon for Postgres and Cloudflare R2 for blob
storage. The unit of deployment is a container image built from the
multi-stage [`Dockerfile`](Dockerfile), which produces an
`eclipse-temurin:25-jre` image with the uberjar baked in and
`ENTRYPOINT ["java", "-jar", "/app/reader.jar", "prod.edn"]`.

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

### One-time Fly.io setup

Fly app names are globally unique across the entire platform, so
the default `reader` is almost certainly taken. Edit `fly.toml`'s
`app = "..."` to something namespaced to you (e.g.
`kirahowe-reader`) first, then:

```sh
flyctl auth login   # if not already
bb fly:init         # create the Fly app named in fly.toml
bb deploy
```

### Configuration

Configuration is EDN, not env vars. `base-system.edn` is always
loaded; the profile passed on the command line is meta-merged on
top:

```
resources/base-system.edn          # defaults, always loaded
env/dev/resources/dev.edn          # bb dev → -m reader.dev.main
env/test/resources/test.edn        # bb test
env/prod/resources/prod.edn        # baked into the uberjar
```

The few values that must come from the environment — the HTTP `PORT`,
and in prod the `DATABASE_URL` Neon connection string — are pulled in
inline via reader literals like `#env/long ["PORT" 8080]` and
`#env "DATABASE_URL"`.

## Useful docs

- [`docs/principles.md`](docs/principles.md) — how this codebase is built and why
- [`docs/architecture.md`](docs/architecture.md) — components and request lifecycles
- [`docs/data-model.md`](docs/data-model.md) — entities and relationships
- [`docs/roadmap.md`](docs/roadmap.md) — the path from scaffold to useful
- [`docs/adr/`](docs/adr/) — decision records

## License

MIT — see [LICENSE](LICENSE).
