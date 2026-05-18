# Architecture

A snapshot of how Reader is wired together as of the v0.1 scaffold.
This is a "where are the boxes and what flows between them" view — the
data-model document covers the shape of state at rest, and the ADRs
cover *why* each piece is the way it is.

## System diagram

```mermaid
flowchart TB
  subgraph clientZone["Client"]
    browser["Browser<br/><i>HTMX + Alpine + vanilla CSS</i>"]
  end

  subgraph cloudflare["Cloudflare edge"]
    emailrt["Email Routing<br/><i>r-7f3a9b2c@reader.kira.is</i>"]
    worker["Email-Routing Worker<br/><i>parses + forwards</i>"]
    r2[("R2<br/><i>PDFs, raw emails</i>")]
  end

  subgraph fly["Fly.io machine"]
    direction TB
    httpkit["http-kit server<br/><i>:8080</i>"]
    reitit["Reitit router<br/><i>+ Malli coercion</i>"]
    mw["Ring middleware<br/><i>logging, auth, anti-forgery</i>"]

    subgraph domain["Domain handlers"]
      reading["reader.reading<br/><i>queue, mark read</i>"]
      lib["reader.library<br/><i>authors, affiliations</i>"]
      inbound["reader.inbound<br/><i>/api/inbound</i>"]
      authui["reader.auth<br/><i>session, magic-link</i>"]
    end

    subgraph workers["core.async workers"]
      pdfwork["PDF ingestion"]
      emailwork["Email ingestion"]
    end

    mulog["mu/log publisher"]
    cache["core.cache<br/><i>LRU caches</i>"]

    httpkit --> mw --> reitit --> domain
  end

  neon[("Neon<br/><i>managed Postgres</i><br/>authors, affiliations,<br/>readables, queue, jobs")]

  subgraph hanko["Hanko"]
    hk["passwordless auth"]
  end

  ig{{"Integrant system<br/><i>owns every lifecycle</i>"}}

  browser <-->|HTTPS<br/>HTML over HTMX| httpkit
  emailrt --> worker
  worker -->|signed POST /api/inbound| httpkit
  worker -->|put raw .eml| r2

  domain -->|next.jdbc + HoneySQL| neon
  domain -->|S3 SDK| r2
  domain <-->|JWT verify| hk
  authui <-->|magic links| hk

  workers --> neon
  workers --> r2

  domain -.publishes events.-> mulog
  workers -.publishes events.-> mulog

  ig -.starts/stops.-> httpkit
  ig -.starts/stops.-> workers
  ig -.starts/stops.-> mulog
  ig -.starts/stops.-> cache

  classDef ext fill:#f4f1ec,stroke:#999,color:#333
  classDef ig fill:#e8f0eb,stroke:#2b4a3f,color:#1a1a1a
  class cloudflare,hanko ext
  class ig ig
```

## Boundaries and trust

The system has three trust boundaries:

| From                    | To       | What crosses                            | Validated by                              |
| ----------------------- | -------- | --------------------------------------- | ----------------------------------------- |
| Browser                 | http-kit | HTTP requests                           | Malli coercion at the route               |
| Cloudflare Email Worker | http-kit | Inbound email payloads (`/api/inbound`) | Shared-secret signature + Malli on body   |
| Hanko                   | http-kit | JWT in session cookie                   | Hanko-issued JWT verification             |

Inside the Fly machine, everything is first-party code. The
"core/shell" discipline (pure logic vs. effectful edges) is an
organizing principle within the codebase, not a runtime boundary.

## Stateful components owned by Integrant

Every cell in this table has a `defmethod ig/init-key` and a
matching `defmethod ig/halt-key!`. Each is configured once at startup
and passed to its consumers — none of these are looked up globally
at use-time.

| Component                                    | Owns                                                |
| -------------------------------------------- | --------------------------------------------------- |
| `:reader.log/publisher`                      | the mu/log publisher                                |
| `:reader.concerns/http-kit`                  | the http-kit server (port, host, lifecycle)         |
| `:reader.concerns.reitit/ring-handler`       | the assembled Ring handler                          |
| `:reader.concerns.reitit/router`             | the Reitit router, with routes data from EDN        |
| `:reader.concerns.reitit/default-handler`    | unmatched-route handling (404/405/trailing-slash)   |
| `:reader.handlers/home`                      | `GET /` — landing page                              |
| `:reader.handlers/health`                    | `GET /health` — liveness probe                      |
| `:reader.handlers/static`                    | `GET /static/*` — classpath resource handler        |
| `:reader.db/datasource` *(soon)*             | the Postgres connection pool (Neon)                 |
| `:reader.db/migrator` *(soon)*               | Migratus runner, runs once at startup               |
| `:reader.storage/r2` *(soon)*                | the S3 SDK client wired to R2                       |
| `:reader.cache/lru` *(soon)*                 | a named core.cache instance                         |
| `:reader.jobs/worker` *(soon)*               | a core.async loop draining the `jobs` table         |
| `:reader.inbound/parser` *(soon)*            | email parsing pipeline                              |

`concerns/` (server, router, default-handler) cover library-specific glue code,
and `handlers/` (one ig key per route handler) are our app/domain-specific code.
Routes data lives in EDN — each leaf is an `#ig/ref` to a handler key, adding a
route is a config change plus a handler init-key, not a router edit.

## Request lifecycle: typical page render

```mermaid
sequenceDiagram
  autonumber
  participant B as Browser
  participant H as http-kit + Reitit
  participant D as domain handler
  participant DB as Postgres (Neon)
  participant L as mu/log

  B->>H: GET /queue (cookie: session JWT)
  H->>H: auth middleware verifies JWT (Hanko-issued)
  H->>D: handler(req {user-id})
  D->>L: ::queue-fetch-start {user-id}
  D->>DB: SELECT readables JOIN queue_items WHERE user_id = ?
  DB-->>D: rows
  D->>L: ::queue-fetch-done {user-id :count n}
  D-->>H: hiccup -> string -> ring response
  H-->>B: 200 text/html
```

## Request lifecycle: inbound email

```mermaid
sequenceDiagram
  autonumber
  participant E as Inbound email
  participant CR as Cloudflare Email Routing
  participant CW as Cloudflare Worker
  participant R as R2 bucket
  participant H as http-kit /api/inbound
  participant J as jobs worker
  participant DB as Postgres (Neon)

  E->>CR: SMTP to r-7f3a9b2c@reader.kira.is
  CR->>CW: raw email
  CW->>R: PUT raw .eml (object key)
  CW->>H: POST /api/inbound (HMAC-signed)
  H->>H: verify signature, Malli-validate body
  H->>DB: INSERT jobs (queue=:ingest-email, payload={r2-key, alias})
  H-->>CW: 202 Accepted
  J->>DB: SELECT next pending job
  J->>R: GET raw .eml
  J->>J: parse headers, body, find/create author + affiliation
  J->>DB: INSERT newsletter_issue + authorships + queue_item
  J->>DB: UPDATE job SET state='done'
```

## Deployment topology

```mermaid
flowchart LR
  dev["Local dev<br/>bb dev"] -->|git push| gh["GitHub"]
  gh -->|on push to main| ga["GitHub Actions<br/>bb ci"]
  ga -->|on green| fly["flyctl deploy"]
  fly --> machine["Fly machine<br/>eclipse-temurin:25-jre<br/>+ reader.jar"]
  machine -->|JDBC over TLS| neon[("Neon<br/>managed Postgres")]
  machine -->|S3 SDK| r2[("R2 bucket")]
```

Every step is invocable as `bb <task>` locally; CI is a thin shim.
