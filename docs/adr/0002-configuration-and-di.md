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
per-environment EDN profiles onto a base config. **`deps.edn` aliases**
both place the right `env/<env>/resources/` on the classpath and pass
the profile filename to `reader.main`.

### File layout

```
resources/base-system.edn          # base, always loaded
env/dev/resources/dev.edn          # dev profile   (loaded via :dev)
env/test/resources/test.edn        # test profile  (loaded via :test)
env/prod/resources/prod.edn        # prod profile  (loaded via :prod, baked into uberjar)
env/dev/src/user.clj               # dev-only REPL entry point
env/dev/src/dev.clj                # dev-only integrant.repl wiring
```

### Loading pipeline

`reader.main` exposes the loading pipeline as discrete functions:
`load-config` reads one profile, `merge-profiles` meta-merges a list,
`prep-config` adds `ig/load-namespaces`, and `exec-config` finishes
with `ig/init`. `core-profiles` is the list that's always loaded
(`base-system.edn`). An additional profile is named explicitly:

- `clojure -M:dev` runs `reader.main` with `"dev.edn"` as its arg.
- `clojure -M:prod` runs `reader.main` with `"prod.edn"`.
- Tests call `main/prep-config` directly with the profiles they need.

There is no `READER_PROFILE` env var. The profile name is the
argument; the deps alias supplies it.

### Component-namespace conventions

Init-keys are organized by concern, mirroring the patterns used by the
reference projects we drew from:

- `reader.concerns.<thing>` — lifecycle/wiring keys for an external
  technology or a Reitit subsystem (e.g. `:reader.concerns/http-kit`,
  `:reader.concerns.reitit/{ring-handler,router,default-handler}`).
- `reader.handlers/<name>` — one init-key per route handler, returning
  a Ring handler fn. Routes data in `base-system.edn` wires them with
  `#ig/ref`.
- `reader.concerns.integrant` — EDN reader literals (`#env`, `#env/opt`,
  `#env/long`, `#env/bool`, `#env/secret`, `#resource`) and the
  `:reader/const` init-key. Any key meant to expose a literal value to
  the rest of the graph derives from `:reader/const`.

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
| `#resource`   | `clojure.java.io/resource` — turns a classpath path into a URL. |

Each accepts a bare name or a `[name default]` vector:

```clojure
{:reader.concerns/http-kit
 {:opts {:port #env/long ["PORT" 8080]}}}
```

Configuration stays *in one place* — the EDN — even when some values
come from outside.

## Consequences

There is one place to look to understand what the running application
is: the merged Integrant config map. It is data, fully introspectable
at the REPL.

Tests stand up subsets of the system by handing `ig/init` a list of
keys. The HTTP integration tests do exactly this — one suite inits
only `:reader.concerns.reitit/ring-handler` (no socket), another inits
`:reader.concerns/http-kit` on an ephemeral port for over-the-wire
checks.

Secrets cannot accidentally print themselves into a log line —
`Secret` is a record whose `toString` returns a placeholder.
