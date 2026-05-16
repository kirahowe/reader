# ADR 0005: Deployment, infrastructure, and developer tooling

- **Status**: Accepted
- **Date**: 2026-05-16

## Context

Reader is a personal-scale app deployed by one person. The operational
story has to be small, scriptable, and survive long periods of
inattention. Inbound email (for newsletter ingestion) needs an
SMTP-receiving surface we don't want to run ourselves. We need cheap,
durable blob storage for PDFs and raw `.eml` files. We need
authentication that doesn't require us to manage passwords.

## Decision

| Concern              | Choice                                    |
| -------------------- | ----------------------------------------- |
| Application hosting  | **Fly.io**                                 |
| Database             | **Postgres on a Fly volume** (v1) → managed Postgres later |
| Blob storage         | **Cloudflare R2** via the AWS S3 SDK       |
| Inbound email        | **Cloudflare Email Routing** → a **Worker** → our `/api/inbound` |
| Authentication       | **Hanko** (passwordless / magic links)     |
| Container base       | **eclipse-temurin:25-jre** (Java 25 LTS)   |
| CI                   | **GitHub Actions** (thin shim over `bb`)   |
| Developer tasks      | **Babashka** (`bb.edn`) — no Makefile      |
| Local infra          | **docker-compose** (Postgres + MinIO)      |
| Migrations           | **Migratus**                               |

### Fly.io

A single Fly machine in `yyz`, scaling to zero when idle and back up
on first request. `fly.toml` describes the machine spec, the health
check (`GET /health`), and the auto-stop policy. Deploys are
`flyctl deploy --remote-only` — invoked from CI on every merge to
`main`, or by a human running `bb deploy`.

### Cloudflare R2

R2 is S3-compatible, cheap at our scale, and has no egress fees. We
talk to it with the AWS S3 SDK because the SDK is stable and the
S3 protocol is universal. Object keys (PDFs, raw emails) are stored in
the Postgres row that owns them; the bytes live in R2.

### Cloudflare Email Routing + Worker

Each user has an inbound alias like `kira-abc@reader.kira.is`.
Cloudflare Email Routing receives the SMTP message and forwards it to
a Worker we own. The Worker:

1. Writes the raw `.eml` to R2.
2. Sends a signed HTTPS POST to `https://reader.kira.is/api/inbound`
   with `{alias, r2-key, from, subject}`.

The application validates the signature, enqueues a `:ingest-email`
job, and returns `202 Accepted` immediately. Job workers do the
parsing.

There is **exactly one inbound adapter**: Cloudflare Worker. We do not
ship an unused SendGrid or Postmark adapter "in case we need it." If
we ever switch providers, the new adapter replaces this one.

### Hanko

Hanko is passwordless authentication with magic links and (later)
passkeys. It runs as a managed service. The application receives a
Hanko-issued JWT in a session cookie, verifies it on each request, and
maps the subject to a `users` row by `hanko_id`.

This keeps us out of the password-management business entirely.

### eclipse-temurin:25-jre

The runtime container. Temurin is Eclipse Adoptium's OpenJDK build,
the de-facto community JDK. Java 25 is the September 2025 LTS, so
we're set for years on this base.

### GitHub Actions, but thinly

The CI workflow runs `bb ci` (which itself runs lint + fmt-check +
test). The deploy workflow runs `flyctl deploy --remote-only`. All
real CI logic lives inside `bb.edn` and the Clojure functions it
calls, so moving off GitHub Actions later would not require rewriting
anything substantive — only the YAML shim.

### Babashka tasks (`bb.edn`), no Makefile

**Every** developer-facing operation is a `bb <task>`: `dev`, `test`,
`lint`, `fmt`, `build`, `image`, `deploy`, `infra:up`, `infra:down`.
This means:

- One language across server, build, and tasks: Clojure.
- Tasks are introspectable (`bb tasks` lists them).
- No tab-vs-space traps. No shell-quoting horrors.
- Anything more than a one-liner can graduate to a Clojure file
  invoked from `bb.edn`.

### docker-compose for local infra

`bb infra:up` brings up Postgres and MinIO locally for development.
MinIO stands in for R2 in dev (same S3 API).

### Migratus

Migrations are SQL files in `resources/migrations/`. Migratus runs
them in order at startup as an Integrant component. No ORM-generated
migrations. Schema changes are committed alongside the code that
needs them.

## Consequences

### What we gain
- Cheap, durable hosting that scales to zero.
- A blob layer that we can fail over without rewriting application
  code.
- An inbound-email pipeline we don't have to operate.
- No password storage to leak.
- CI we can move at any time without rewriting build logic.
- A single tasks file (`bb.edn`) that any new contributor can read top
  to bottom in two minutes.

### What we accept
- Vendor lockin on Fly, Cloudflare, and Hanko at the platform level.
  These are decisions we'd revisit if any one of them became
  meaningfully worse than alternatives; the application code itself is
  not coupled to them beyond `flyctl deploy`, the S3 SDK calls (which
  work against any S3-compatible store), the inbound webhook contract,
  and Hanko JWT verification.
- A Postgres on a Fly volume is single-tenant and not HA. v1 personal
  use does not need HA. We move to a managed Postgres before that
  matters.

## Alternatives considered

### Hosting: Render, Railway, fly.io, a $5 VPS
Render and Railway both work. Fly's `flyctl` is pleasant, its global
machine model fits us, and the auto-stop economics matter. A $5 VPS
is cheaper but adds OS-maintenance work we don't want.

### Blob: AWS S3, Backblaze B2, R2
S3 has the highest egress cost. B2 is excellent. R2's zero-egress and
S3-API compatibility make it the natural fit.

### Inbound email: SES inbound, Postmark inbound, run our own SMTP
SES inbound is fiddly to configure and bills per email. Postmark is
solid but isn't free. Running our own SMTP is not what we want to
spend time on. Cloudflare Email Routing is free and pairs cleanly
with a Worker.

### Auth: build our own / Clerk / Auth0 / WorkOS / Hanko
Build our own — rejected; we don't want to be in the password
business. Clerk/Auth0/WorkOS — fine but expensive for personal use.
Hanko is open-source, passwordless-first, has a generous free tier,
and gets us out of password management.

### Build tool: Leiningen, Boot, depstar, tools.build
tools.build is the modern, official answer. We picked it in ADR 0002
but mention it again because it's the only piece of the *build*
toolchain we ship.

### Migrations: Migratus, Ragtime, Honeysql-Migrations, EDN-only
Migratus is the most-used Clojure migration runner and has a simple
mental model (timestamped pairs of `up.sql` / `down.sql`). Ragtime is
fine. We'd switch if we hit a real reason; we haven't.

### Local infra: Postgres on the host, devcontainer, dockerised infra
Dockerised infra via `docker-compose` is the lowest-friction option
that works the same on every developer's machine. It's the same
Postgres version we run in prod.
