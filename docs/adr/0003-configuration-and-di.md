# ADR 0003: Configuration and dependency injection

- **Status**: Accepted
- **Date**: 2026-05-16

## Context

Every non-trivial application has to answer three questions:

1. How are stateful components (DB pools, servers, workers) started and
   stopped?
2. How is configuration sourced (defaults, per-environment overrides,
   secrets)?
3. How do components find their dependencies (DB pool, config values)?

The Clojure ecosystem has multiple answers — component, mount,
integrant, donut.system, Aero, juxt/aero, environ, cprop — and getting
this wrong is expensive to fix. The user has strong opinions: no
global atoms in our code, no `defstate`, lifecycle is explicit and
owned, configuration is in files not in env vars.

## Decision

We use **Integrant** for dependency injection and lifecycle. We use
**`meta-merge`** to layer per-environment EDN files on top of a base
config. We use **`deps.edn` aliases** to select which overlay is
active. We do **not** use Aero.

The pieces in detail:

### Base config + per-environment overlay

```
resources/system/base.edn              # production-safe defaults
resources-dev/system/overlay.edn       # dev overrides
resources-test/system/overlay.edn      # test overrides
resources-prod/system/overlay.edn      # prod overrides (production)
```

Each environment is a `deps.edn` alias that adds the corresponding
`resources-*` directory to the classpath:

```clojure
:dev   {:extra-paths ["dev" "resources-dev"]  …}
:test  {:extra-paths ["test" "resources-test"] …}
:prod  {:extra-paths ["resources-prod"]        …}
```

`kira.reader.system/config` reads `system/base.edn` and
`system/overlay.edn` from the classpath, meta-merges them, drops keys
whose merged value is `nil` (so an overlay can *remove* a component),
and hands the result to `integrant.core/init`.

### Env-var reader literals

The few values that *must* come from the environment use EDN reader
literals registered with the reader:

| Reader        | Behavior                                                   |
| ------------- | ---------------------------------------------------------- |
| `#env`        | Required env var; throws if missing.                       |
| `#env/opt`    | Optional env var; returns nil if missing.                  |
| `#env/long`   | Required env var, parsed as long.                          |
| `#env/bool`   | Required env var; true if `"true"`/`"1"`/`"yes"`.          |
| `#env/secret` | Required env var, wrapped in a `Secret` whose `toString` returns `<secret>`. |

Each reader accepts either a bare name (`#env "PORT"`) or a
name+default vector (`#env/long ["PORT" 8080]`). They appear in EDN
exactly where the value would have been:

```clojure
{:kira.reader.http/server
 {:port #env/long ["PORT" 8080]}}
```

### Integrant owns every lifecycle

Every value that has a start and a stop is an Integrant component with
matching `defmethod ig/init-key` / `defmethod ig/halt-key!`. This
includes the HTTP server, the mu/log publisher, the DB pool, the R2
client, every core.async worker, and every named cache.

Application code never reaches into globals to find these. They are
passed as arguments — to handlers, to domain functions, to workers.

### REPL workflow

`integrant.repl` provides `(go)`, `(halt)`, `(reset)` in `dev/user.clj`.
`reset` halts the system, recompiles changed namespaces, and starts a
fresh system. The repl-state atom inside `integrant.repl` is the *only*
mutable cell in the dev path; our own code holds none.

## Consequences

### What we gain
- The application has one place to look for "what's running": the
  Integrant config map.
- Configuration is structured, typed (at the value level), and
  composable. No flat env-var pile.
- Environments differ in known, small, declared ways.
- Tests can stand up a subset of the system — just the DB, just the
  router with a stub DB — by trimming the config.
- Secret hygiene: secrets are wrapped in a `Secret` record so they
  cannot accidentally print themselves into a log line.

### What we accept
- Boilerplate: a defmethod per init-key, a defmethod per halt-key. This
  is real work. We accept it because the alternative — implicit global
  state — is a debt that compounds.
- Reading the config requires the reader namespaces to be loaded. We
  do this explicitly inside `kira.reader.system`. There is no global
  `data_readers.cljc` that fires during arbitrary EDN reads; the
  scoping is tight.

## Alternatives considered

### Aero
We considered Aero specifically for its rich reader literals. Rejected
because Integrant already supports custom reader literals via its
`ig/read-string` and we don't need another configuration system layered
on top. One mechanism, locally owned.

### Component
Older than Integrant, harder to start partial systems from, more
ceremony, less idiomatic data-driven config. Integrant is the modern
answer to the same problem.

### mount / `defstate`
Globally mutable, namespace-driven, undermined by AOT and by REPL
reloads. The user explicitly does not want this.

### donut.system
A reasonable alternative to Integrant with a more nested config shape.
Less ecosystem support today; doesn't earn the migration cost.

### Profile-selection via env var
A single env var like `READER_PROFILE=prod` to pick the overlay was
considered. Rejected because:

- it puts a critical wiring decision in a flat string env var, which
  is the opposite of where we want it;
- it forces every script and tool to remember to set it;
- `deps.edn` aliases solve this cleanly: the alias *is* the profile.

### Config as a single big EDN with profile keys
Considered and rejected. Encourages parallel diffs between
environments to drift apart, and forces all environments to be in one
file (so a test process pulls in prod URLs at read-time even if it
doesn't use them — which matters when reader literals run).
