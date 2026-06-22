# ADR 0004: Deployment and infrastructure

- **Status**: Accepted
- **Date**: 2026-05-17
- **Amended**: 2026-06-21 (cold-start tuning)

## Context

Reader is a personal-scale app operated by one person. The
infrastructure has to be small, scriptable, and survive long periods
of inattention. Inbound email (for newsletter ingestion) needs a
receiving surface that isn't an SMTP server. Blob storage is needed
for PDFs and raw `.eml` files. Authentication should not involve
holding any passwords.

## Decision

| Concern               | Choice                                            |
| --------------------- | ------------------------------------------------- |
| Application hosting   | Fly.io                                            |
| Database              | Neon (managed Postgres)                           |
| Blob storage          | Cloudflare R2 via the AWS S3 SDK                  |
| Inbound email         | Cloudflare Email Routing → Worker → `/api/inbound` |
| Authentication        | Hanko (passwordless, magic links, passkeys)        |
| Runtime               | `eclipse-temurin:25-jre` (Java 25 LTS)             |
| CI                    | GitHub Actions, a thin shim over `bb` tasks        |
| Tasks                 | Babashka (`bb.edn`)                                |
| Local infra           | testcontainers, lifecycled by Integrant (when DB lands) |
| Migrations            | Migratus                                           |

### Neon for Postgres

Neon is serverless Postgres: scales the compute to zero when idle,
spins back up on first query, charges for what's used. For a personal
app that goes hours or days between requests, the cost difference vs.
an always-on managed Postgres is large. Neon also has database
branching, which makes preview/test environments trivial.

A Postgres-on-a-Fly-volume alternative was considered and rejected: it
requires sizing, operating, backing up, and monitoring a database
process — work that adds zero application value and competes with
time spent on the app itself. Fly themselves now point at Neon and
Supabase as the recommended managed-Postgres options.

### Fly.io for the app

A single Fly machine in `yyz`, scaling to zero when idle and back up
on first request. Fly's auto-stop economics fit the same usage shape
as Neon. Deploys are `flyctl deploy --remote-only`, run from CI on
green merges to `main` or by a human via `bb deploy`.

### Cold-start tuning (amended 2026-06-21)

Scaling to zero puts the JVM cold boot on the request path: the first
request after idle has to start the machine *and* boot the app before
the HTTP server binds `8080`. Fly's proxy retries that connect for only
~8s before returning `[PM05]`, and that window is not configurable — so
a boot slower than the window drops the request. The choice is to keep
scale-to-zero (it's the whole cost shape, shared with Neon) and shrink
boot to fit the window, rather than keep a machine warm.

Three levers, all preserving `min_machines_running = 0`:

- **AOT-compile every namespace** (`build.clj`). The system is wired in
  EDN and its namespaces are pulled in at runtime by
  `ig/load-namespaces`, so they were invisible to a `reader.main`-rooted
  compile graph and were compiled *from source on every cold boot*.
  Compiling all of `src` moves that cost to build time — namespace
  loading dropped from ~2.2s to ~0.6s in local measurement.
- **JVM fast-start flags** (`Dockerfile` ENTRYPOINT): `-XX:+UseSerialGC`
  (no concurrent GC threads contending with the app on a single shared
  vCPU) and `-XX:TieredStopAtLevel=1` (skip the C2 JIT). Both trade peak
  throughput for startup speed — the right trade for a low-traffic
  machine that lives a few minutes.
- **1gb VM memory** for Clojure's metaspace + code cache. Still a flat
  cost.

**Rejected (kept as a fallback): a warm machine** (`min_machines_running
= 1`). It's the only *hard* guarantee against a cold-start miss, but the
worker polls the jobs table ~1/s, so an always-running machine keeps
Neon awake continuously — undoing the scale-to-zero cost shape on both
sides. It becomes attractive only paired with worker poll-backoff (so an
idle machine stops querying Neon), which is separate planned work.

**If the boot speedups prove insufficient**, the next scale-to-zero-safe
levers are an AppCDS archive baked at build time (cut classloading via a
training run that loads namespaces without `ig/init`), Clojure direct
linking at AOT (fewer var indirections — but it forecloses runtime var
redefinition, so verify nothing in the prod path relies on it), and, as
the heavy option, CRaC (checkpoint a warmed JVM, restore in ms).

### Cloudflare R2 + Email Routing Worker

R2 is S3-compatible and free of egress charges, which dominates the
storage cost at this scale. The application talks to R2 through the
AWS S3 SDK so the protocol is universal.

Each user has a random inbound alias (e.g.
`r-7f3a9b2c@reader.kira.is`). Cloudflare Email Routing accepts the
SMTP message and forwards it to a Worker, which:

1. Writes the raw `.eml` to R2.
2. Signs and POSTs `{alias, r2-key, from, subject}` to
   `https://reader.kira.is/api/inbound`.

The application validates the signature, enqueues an
`:ingest-email` job, and returns `202 Accepted`. Workers do the
parsing.

There is one inbound adapter. No SendGrid stub, no Postmark stub.
If the provider ever changes, the adapter is replaced — not
abstracted-over-in-advance.

### Hanko for auth

Hanko handles passwordless authentication (magic links, passkeys).
The application receives a Hanko-issued JWT in a session cookie,
verifies it on each request, and maps the subject to a `users` row by
`hanko_id`. No password storage in the app.

### `bb.edn` for tasks

Every developer-facing operation is a `bb <task>`: `dev`, `test`,
`lint`, `fmt`, `build`, `image`, `deploy`. One language across server,
build, and tasks. CI is a thin shim that runs `bb ci`; switching CI
providers later is a YAML change, not a rewrite.

Anything more complex than a one-liner graduates to a Clojure function
called from `bb.edn`. No shell scripts. No Makefile.

## Consequences

The infrastructure is four vendors (Fly, Neon, Cloudflare, Hanko),
each chosen for a specific job and each replaceable through a small,
well-defined boundary: `flyctl deploy`, a JDBC URL, S3 SDK calls (any
S3-compatible store), the inbound-webhook contract, and Hanko JWT
verification.

There is no in-house operational surface: no databases to back up by
hand, no SMTP server, no password hashing, no secret rotation
beyond the platforms' own UIs.

Local development will match production through testcontainers — the
same Postgres image and an S3-compatible blob store (MinIO via
testcontainers) brought up as Integrant components in the dev profile
when those consumers land. No `docker-compose` step, no globally
running services, no "works on my machine" gap.
