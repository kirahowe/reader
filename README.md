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

Docker isn't needed yet. Once the data layer lands, local Postgres and
R2 will be lifecycled by Integrant via
[testcontainers](https://java.testcontainers.org/) — Docker becomes a
runtime dependency at that point, not a separate `compose up` step.

## Run it locally

Start a REPL with the `dev` alias and run 

```clojure
(dev)
(go)
```

The app will be available at http://localhost:3000 (or whatever port
is configured in `dev.edn`).

Other useful tasks (run `bb tasks` for the full list):

| Task           | What it does                                  |
| -------------- | --------------------------------------------- |
| `bb lint`      | clj-kondo over `src`, `test`, `env`           |
| `bb fmt`       | cljfmt check                                  |
| `bb fmt:fix`   | cljfmt fix                                    |
| `bb ci`        | lint + fmt-check + tests (what CI runs)       |
| `bb build`     | build the production uberjar                  |
| `bb image`     | build a `reader:latest` container image       |

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
env/dev/resources/dev.edn          # bb dev → -m reader.main dev.edn
env/test/resources/test.edn        # bb test
env/prod/resources/prod.edn        # baked into the uberjar
```

The few values that must come from the environment (port, secrets)
are pulled in inline via reader literals like `#env/long ["PORT" 8080]`.

## Useful docs

- [`docs/principles.md`](docs/principles.md) — how this codebase is built and why
- [`docs/architecture.md`](docs/architecture.md) — components and request lifecycles
- [`docs/data-model.md`](docs/data-model.md) — entities and relationships
- [`docs/roadmap.md`](docs/roadmap.md) — the path from scaffold to useful
- [`docs/adr/`](docs/adr/) — decision records

## License

MIT — see [LICENSE](LICENSE).
