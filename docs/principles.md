# Web App Principles

This is a record of how we think a web application should be built. It's
written as a guide for future contributors so that the values that shaped
this codebase don't have to be re-derived by reading commits. Most of these
are opinions, not rules — but they're the opinions the project starts from,
and deviations should be justified, not assumed.

---

## 1. State is a liability. Make it explicit, lifecycled, and owned.

Stateful things — database connection pools, HTTP servers, background
workers, in-memory caches, message queues — must have an explicit
**lifecycle**: something starts them, something stops them, and someone
owns that lifecycle.

In this project, **Integrant owns every stateful component**. There are no
`defonce` atoms, no `defstate` declarations, no top-level connection pools
hiding inside namespaces. If a value has a lifecycle, it lives in the
Integrant system, and the rest of the app receives it as an argument.

Why this matters:

- The system is fully startable and stoppable from any process — a test, a
  REPL, a one-off script — without surprises.
- Restarting in the REPL is reliable. `(reset)` always works, because
  every component knows how to halt itself.
- Tests can stand up a subset of the system (just the DB, say) and tear it
  down cleanly.
- There is one place to look to understand "what's running."

Corollary: **functions take their dependencies as arguments.** A handler
that needs the database does not reach into a global; it accepts a `db`
argument. The wiring that supplies `db` is the Integrant config.

---

## 2. Configuration belongs in files, not in the environment.

Environment variables are a poor configuration mechanism. They're flat,
stringly typed, deploy-tool-specific, leak into child processes, and tempt
people to put 40 of them in a Helm chart.

Use them **sparingly**, for the small number of things that genuinely
*are* environmental: the port the platform tells you to bind to, secrets
injected by your secret store, anything that legitimately differs per
machine.

Everything else — feature flags, retry counts, queue names, log levels,
which publisher mu/log uses, the URL of a sibling service — is just
configuration. It belongs in **per-environment EDN files** that get
**meta-merged** on top of a base config at startup.

The pattern is:

```
resources/system/base.edn              ; defaults — production-safe
resources-dev/system/overlay.edn       ; dev overrides
resources-test/system/overlay.edn      ; test overrides
resources-prod/system/overlay.edn      ; prod overrides
```

Each environment is a `deps.edn` alias that adds the right
`resources-*` directory to the classpath. The application loads
`system/base.edn`, then `system/overlay.edn` (whichever the classpath
resolves to), and meta-merges them.

For the small number of values that *must* come from the environment
(PORT, secrets), use EDN reader literals — `#env`, `#env/long`,
`#env/bool`, `#env/secret` — registered with the reader. They appear
inside the overlay EDN exactly where the value would have been:

```clojure
{:kira.reader.http/server
 {:port #env/long ["PORT" 8080]}}
```

This keeps the configuration *in one place* even when some of its values
come from outside. There is no `READER_PROFILE=prod` env var to decide
which overlay to load — that's what aliases are for.

---

## 3. Use aliases for profile selection. Not env vars.

A "profile" (dev, test, prod) is a property of *how you started this
process*, not of the runtime environment. Use `deps.edn` aliases:

```
clojure -M:dev          # dev profile
clojure -M:test         # test profile
clojure -M:prod         # prod profile (used by the uberjar build)
```

Each alias adds the corresponding `resources-<env>/` to the classpath.
The system code asks for `system/overlay.edn` and gets whichever one is
visible. This is composable with other aliases (`:dev:test` works), trivial
to reason about, and removes the entire class of "what's the value of
`READER_PROFILE` again?" bugs.

---

## 4. HTML should be semantic. Classes are not a styling system.

Use the right tag for the job. `<article>`, `<nav>`, `<section>`,
`<aside>`, `<main>`, `<header>`, `<footer>`, `<button>`, `<a>`,
`<ul>` / `<ol>`, `<dl>`. Lists are lists. Buttons are buttons. Links go
somewhere; buttons do something. Get this right and accessibility, SEO,
and screen-reader behavior are mostly free.

**Do not encode styling concerns in the HTML.** No `class="mt-4 px-6
text-gray-700 hover:bg-blue-50"`. Tailwind is banned. So is BEM. So is
the broader pattern of inventing a class per visual variation.

**Classes are for *semantic* distinctions** that CSS can't reach with a
selector. `<p class="muted">` is fine because the paragraph is
semantically a quiet aside. `<button class="primary-action-cta-large">`
is not — that's styling leaking into the markup.

A well-styled site has surprisingly few classes. If we end up with a
class per visual variant, the design system has failed and we should
revisit it.

---

## 5. CSS: tokens first, layout second, components last.

The CSS layer has three concerns, in order:

1. **Design tokens** — colours, font stacks, spacing scale, measure.
   Defined as CSS custom properties on `:root`, with a
   `@media (prefers-color-scheme: dark)` block that redefines them for
   dark mode. Tokens are the single source of truth — everything
   downstream references them.

2. **Element defaults** — selectors on bare tags (`body`, `h1`, `a`,
   `button`, `main`). This is where most of the visual identity lives.

3. **Component classes** — only when an element genuinely has a
   *semantic* variant that CSS can't express otherwise.

The aesthetic is minimalist Scandinavian: generous whitespace, a single
measured column for reading text, a serif for prose and a clean
sans-serif for UI, restrained colour, real typography. The site should
look reasonable from day one *without* having a design system; we build
out the system as we encounter patterns that recur.

---

## 6. Code is organized by domain, not by architectural layer.

A common temptation is to split source by architectural concern:
`core/`, `shell/`, `domain/`, `infrastructure/`, etc. **Don't.**
That's an *organizing principle for thinking* — the split between pure
core logic and impure shell — but it does not need to be a directory
layout. In Clojure, idiomatic organization is **by domain**:

```
src/kira/reader/
  http/       ; HTTP server, router, middleware
  ui/         ; Hiccup views, layouts, components
  db/         ; queries, migrations interface
  jobs/       ; background work
  inbound/    ; email + worker ingestion
  auth/       ; Hanko integration, session handling
  ...
```

Within a namespace, you can still write pure-core functions and a thin
imperative shell that calls them. The organizing principle survives the
file tree being conventional.

---

## 7. Tasks belong in `bb.edn`. Never in a Makefile.

Makefiles are a foreign syntax, full of tab-vs-space traps, and they
encode build logic in a language that no one on the team writes day to
day. **All developer tasks** — running the app, running tests, linting,
formatting, building the uberjar, building the container image,
deploying, starting local infra — go in `bb.edn` as Babashka tasks.

CI workflows are **thin shims** over `bb` tasks. The GitHub Actions
workflow runs `bb ci`. That's it. If we ever move off GitHub Actions,
nothing of substance moves.

Anything more complex than a one-liner goes into a Clojure file under
`dev/` (or wherever fits) and is called from `bb.edn`. No shell scripts
beyond what trivially fits in a `(shell ...)` form.

---

## 8. No dead code. No "we might need it later" code.

If we're not using something today, it doesn't belong in the repo. No
"adapter layer with three implementations, but two are commented out."
No "we'll switch to Sendgrid eventually so here's a stub for it." Dead
code rots; it confuses readers, breaks under refactors, and accumulates
interest.

If we genuinely need optionality later, we add the *first* alternative
when we need it — and only then are there enough use cases to design the
abstraction. **Three concrete instances is better than one premature
abstraction.**

This applies to data, too: don't add database columns "in case we need
them." Don't precompute hashes we're not using. Don't keep migration
scripts for tables we've deleted.

---

## 9. Polymorphic entities get their own tables.

When we have a concept like "a thing the user can read" that can be one
of several concrete types — articles, papers, newsletter issues —
**give each type its own table**. Don't squash them into one big
`items` table with a `type` column and a `payload` JSON blob.

A paper has a DOI, a body stored in R2 as a PDF, an arXiv ID. A
newsletter issue has a subject line, an HTML body, a sender alias. An
article has a canonical URL and an extracted reading-time estimate. The
shapes diverge sharply. Crushing them into a shared schema forces
nullable everything and pushes type-discrimination into application
code that should have been a `JOIN`.

Polymorphic *references* (e.g., a queue item that points to "one
readable, but it might be any of three types") use a `(type, id)` pair.
The application enforces the integrity check; the database doesn't have
to.

The general rule: **entities that have meaningfully different shapes
get different tables**. Entities that have meaningfully different
relationships also get their own tables — affiliations are first-class
because they have multiple authors, multiple publications, a type, a
URL, and they outlive any individual article. They are not just a
column on `articles`.

---

## 10. Normalize aggressively where it enables querying.

We don't denormalize "for performance" until we have measured a real
problem. We normalize because authors, affiliations, and readables are
nouns the application will want to query along *every* axis:

- All articles from a given affiliation
- All readables by a given author across affiliations
- All affiliations a given author has written for
- Most-read affiliations this month
- New newsletter issues from an affiliation since X

Every one of these is a trivial query against a normalized schema and a
horror against a denormalized one. Pay the upfront cost.

---

## 11. Logging is structured, always.

Use **mu/log**. Log events are maps of keywords to values, not
formatted strings. `(mu/log ::user-signed-in :user-id uid :method
:magic-link)`, not `(log/info (str "User " uid " signed in via magic
link"))`.

In dev, pretty-print to the console. In prod, ship structured JSON to
wherever logs go. The publisher is an Integrant component — it has a
lifecycle and gets started and stopped with the rest of the system.

Log at the **edges** (request in, request out, job start, job finish,
external call out, external call back) and at every **decision
boundary** (we took branch A because X). Don't log inside tight loops.
Don't log secrets — wrap them in a `Secret` record whose `toString`
returns `<secret>`.

---

## 12. Build wide first, then deep.

When starting a project, the first goal is to get the **whole pipeline
running end to end** with a trivial version of every step:

> a hello-world page served by the real HTTP server, in the real
> container, on the real platform, behind the real DNS — and a real CI
> pipeline that deploys it.

Only once that's green do we go back and flesh out each layer. This
catches integration problems early (when there's nothing to debug
yet), exposes platform assumptions (when they're cheap to change), and
makes every subsequent change deployable from day one.

The opposite — building each layer fully before integrating — is how
projects end up with a sophisticated domain layer that has never run on
production infrastructure and an admin tool that no one can reach.

---

## 13. Don't add error handling for impossible cases.

Internal Clojure functions don't need to validate their inputs against
every possible bad value. If a function is only ever called from one
place, and that call site is correct, the function does not need to
defensively check the type of its argument. Trust the rest of the
codebase.

**Validate at boundaries.** When data crosses from outside the system
(HTTP request body, email payload, inbound webhook, a row read from a
table written by an older version of the code), validate it explicitly
with Malli. Inside, work with values you trust.

Fallbacks for things that can't happen turn into bugs over time:
they're paths the test suite doesn't cover, they get out of sync with
the real behavior, and they hide actual mistakes.

---

## 14. The HTTP layer is dumb.

A handler does three things and three things only:

1. Coerces and validates its inputs (Malli)
2. Calls a domain function with the validated input
3. Renders the result (Hiccup, JSON, redirect, whatever)

It does not do business logic. It does not talk to the database
directly except for the simplest read-through cases. It does not
construct queries. If a handler is more than ~20 lines, it's probably
doing something a domain namespace should be doing.

Reitit data routes make this almost automatic — the route is a map, the
middleware stack is a vector, the handler is the leaf — but the
discipline still has to be human.

---

## 15. HTMX + Alpine is the default. JS framework is a last resort.

Server-rendered HTML returned from focused endpoints, swapped into the
page by HTMX, with Alpine for the tiny amount of local interactivity
that's awkward in pure HTML — this covers ~95% of the surface area of a
content-heavy app, with vastly less complexity than an SPA.

If a piece of the UI genuinely needs a rich client (a graph editor, a
collaborative editor, a PDF reader with annotation), it can be a small
island of dedicated JavaScript embedded in the otherwise-server-rendered
page. We do not turn the whole app into an SPA to accommodate the one
view that warrants it.

---

## 16. Background work is durable, not in-memory.

Anything that could matter if the process restarts mid-flight — sending
an email, extracting text from a freshly uploaded PDF, ingesting an
inbound newsletter — goes through a **durable job table** in Postgres.
A worker (an Integrant-managed core.async loop) pulls pending jobs,
runs them, and updates state.

In-memory `chan`s are fine for things that are inherently transient —
streaming progress updates to a logged-in user, coordinating between
parts of a single request. Anything user-visible after a restart is
durable.

---

## 17. Tests cover the system, not just the units.

Unit tests are cheap and worth writing for pure functions. But the
**most valuable tests** are the ones that stand up a real subset of
the Integrant system, hit it with real inputs, and assert on the
outcome. With Integrant + testcontainers, this is barely more code than
a unit test and orders of magnitude more confidence.

Tests should be runnable in any order, in parallel, and without any
shared external state. Each test owns its own data fixtures and cleans
up after itself (or uses transactional rollback).

---

## 18. Deployment is `bb deploy`, not a runbook.

Deploying should be one command. The same command CI runs is the same
command a human runs locally if they ever need to. If the deployment is
manual enough to need a wiki page, the wiki page is the bug and
automation is the fix.

The container image is the unit of deployment. It's reproducible from
the source tree. There is no "and also run this script on the server
once after deploying."

---

## 19. The README is short. The code is the source of truth.

Avoid writing READMEs that try to explain the codebase — they get
stale, they're rarely read, and they encourage the codebase to be
unreadable on its own. The README has: what this is, how to run it
locally in three lines, where the docs live (this folder), and the
license.

Architecture and decisions live in `docs/`. ADRs capture *why* we made
a choice (which is the part that rots last and ages best). The code
itself, with good names, captures *what* it does.
