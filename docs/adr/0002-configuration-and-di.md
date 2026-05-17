# ADR 0002: Configuration and dependency injection

- **Status**: Accepted
- **Date**: 2026-05-17

## Context

Every non-trivial application needs answers to three questions:

1. How are stateful components (DB pools, servers, workers) started
   and stopped?
2. How is configuration sourced (defaults, per-environment overrides,
   the few values that must come from the environment)?
3. How do components find their dependencies at runtime?

Getting these wrong is expensive to fix later: hidden globals leak
through the codebase, environment-variable soup spreads to deployment
scripts, and lifecycles become implicit and unreliable.

## Decision

**Integrant** owns every lifecycle. **meta-merge** layers
per-environment EDN onto a base config. **`deps.edn` aliases** select
which environment overlay is on the classpath.

### File layout

```
resources/base-system.edn          # base, always on the classpath
env/dev/resources/env.edn          # dev overrides   (loaded via :dev)
env/test/resources/env.edn         # test overrides  (loaded via :test)
env/prod/resources/env.edn         # prod overrides  (loaded via :prod, baked into uberjar)
env/dev/src/user.clj               # dev-only REPL helpers
```

`reader.sys/load-configs` reads every classpath instance of
`base-system.edn`, then every classpath instance of `env.edn`, and
meta-merges them. The active `env.edn` is the one the chosen alias put
on the classpath. There is no `READER_PROFILE` env var; the alias *is*
the profile.

### Lifecycle ownership

Every value with a start and a stop has a `defmethod ig/init-key`
and a matching `defmethod ig/halt-key!`. No `defonce` atoms, no
`defstate`, no top-level connection pools hiding in namespaces. Application code
receives its dependencies as arguments; it does not reach into globals.

This is not a stylistic choice. Implicit global state causes startup
ordering bugs that only show up under load or during reloads, makes
partial-system tests impossible, and produces subtle leaks when one
test contaminates the next. Integrant eliminates the entire class.

### EDN reader literals for env vars

The few values that genuinely come from the environment (PORT,
secrets) appear inline in the EDN via reader literals registered with
Integrant's reader:

| Reader        | Behaviour                                              |
| ------------- | ------------------------------------------------------ |
| `#env`        | Required env var; throws if missing.                   |
| `#env/opt`    | Optional env var; returns nil if missing.              |
| `#env/long`   | Required env var, parsed as long.                      |
| `#env/bool`   | Required env var, true for `"true"` / `"1"` / `"yes"`. |
| `#env/secret` | Required env var, wrapped in a `Secret` whose `toString` is `<secret>`. |

Each accepts a bare name or a `[name default]` vector:

```clojure
{:reader.http/server {:port #env/long ["PORT" 8080]}}
```

Configuration stays *in one place* — the EDN — even when some values
come from outside.

## Consequences

There is one place to look to understand what the running application
is: the merged Integrant config map. It is data, fully introspectable
at the REPL.

Tests stand up subsets of the system by handing `ig/init` a list of
keys. The HTTP integration tests do exactly this — one suite inits
only `:reader.http/handler` (no socket), another inits
`:reader.http/server` on an ephemeral port for over-the-wire checks.

Secrets cannot accidentally print themselves into a log line —
`Secret` is a record whose `toString` returns a placeholder.
