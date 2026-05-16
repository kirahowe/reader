# ADR 0002: Server stack and UI approach

- **Status**: Accepted
- **Date**: 2026-05-16

## Context

Within Clojure, there are many reasonable choices for HTTP, routing,
validation, templating, and client-side interactivity. We need to pick
a coherent stack early because the cost of switching grows fast.

The shape of the app is server-rendered HTML with islands of light
client-side interactivity. The user does not want an SPA. The user
does not want to fight a CSS framework.

## Decision

| Layer                     | Choice                              |
| ------------------------- | ----------------------------------- |
| HTTP server               | **http-kit** (`http-kit/http-kit`)  |
| Routing                   | **Reitit** (`metosin/reitit`)       |
| Input validation/coercion | **Malli** (`metosin/malli`)         |
| Server-side templating    | **Hiccup** (`hiccup/hiccup`, v2)    |
| Client interactivity      | **HTMX** + **Alpine.js**            |
| CSS                       | **Vanilla CSS**, no framework       |
| Background work           | **core.async** + a durable jobs table |
| In-memory caches          | **clojure.core.cache**              |
| Structured logging        | **mu/log** (`com.brunobonacci/mulog`) |
| Tests                     | **kaocha** (`lambdaisland/kaocha`)  |
| Build                     | **tools.build** (`clojure tools.build`) |

## Consequences

### http-kit
A small, well-understood HTTP server with native async support and a
trivial integration surface. We rarely need anything more from this
layer. We avoid Jetty/Undertow because we don't need their feature
surface and the operational complexity isn't free.

### Reitit
Data-driven routing means routes are values: introspectable, mergeable,
testable. Reitit's middleware composition is per-route and works
naturally with Malli for coercion. The router compiles to a fast
trie-based matcher. The router *is* the API description; we don't need
a separate OpenAPI spec.

### Malli
Schemas are values. They round-trip through EDN. We can generate test
data, generate JSON Schema for clients, validate at boundaries, and
strip-or-error on unknown fields, all from one description.

### Hiccup
HTML as data. The full Clojure language is available inside templates,
which means partials, helpers, and abstractions are written with the
same tools as the rest of the code. No template-language quirks. Hiccup
v2 (RC) for performance and improved escaping defaults.

### HTMX + Alpine
HTMX moves the "fetch HTML, swap it in" pattern into HTML attributes.
This covers most of what an SPA framework gives us for the kind of app
we are building (forms, lists, mutations, pagination), with no build
step, no client-side router, no state library, no hydration. Alpine
handles the small amount of *purely-local* interactivity (a toggle, a
dropdown, a tab) that's awkward in vanilla HTML.

For the rare view that needs a rich client (a PDF reader with
annotation, an interactive graph), we'll embed a small island of
dedicated JS. We do not turn the whole app into an SPA to accommodate
one view.

### Vanilla CSS, no framework
Tailwind and other utility-class systems make the HTML inseparable from
its styling and produce markup that is unreadable without the styles.
We don't want that. The aesthetic goal — minimalist Scandinavian:
generous whitespace, restrained colour, real typography — is
straightforward to express with:

1. CSS custom properties for design tokens (colours, spacing, fonts,
   measure), with a `prefers-color-scheme: dark` override.
2. Element selectors for the bulk of styling.
3. Sparingly used classes for genuinely *semantic* variants.

We don't ship a design system; we extract patterns from the code as
they recur.

### core.async + durable jobs
In-memory `chan`s are fine for transient coordination. Anything that
matters across a process restart (sending an email, extracting a PDF,
ingesting an inbound newsletter) goes through a Postgres `jobs` table
that a worker (an Integrant-managed core.async loop) drains via
`SELECT ... FOR UPDATE SKIP LOCKED`. This avoids running a separate
queueing system in v1 — Postgres is already on the critical path and
handles this workload trivially.

### core.cache
We don't need Caffeine. core.cache's TTL and LRU strategies cover what
we want, with zero extra dependencies and a Clojure-native API.

### mu/log
Events are maps, not formatted strings. Pretty in dev, JSON in prod.
The publisher is an Integrant component with a real lifecycle.

### kaocha
The de-facto modern Clojure test runner. Good output, watch mode,
randomization. Configured via a simple `tests.edn` (or defaults).

### tools.build
The official build library. We have one `build.clj` with a `uberjar`
function. No Leiningen, no Boot, no shell scripts.

## Alternatives considered

### Pedestal, Aleph, Jetty for the HTTP layer
All overkill for our needs. http-kit has been quietly excellent for
over a decade.

### Compojure, Bidi for routing
Both are fine, but Reitit's data-orientation, middleware composition,
and Malli integration make it strictly better for our usage. The
performance difference is real but secondary.

### spec, schema for validation
spec is great for development-time invariants but its design point is
generative testing, not boundary validation. Malli is purpose-built for
the latter.

### Selmer, comb for templating
String templating languages are weaker than just-write-Clojure for
non-trivial views. We give up nothing real by using Hiccup.

### Tailwind CSS / Bulma / Pico
Tailwind: rejected on stylistic grounds (see above).
Bulma/Pico: better than Tailwind in that they don't pollute the HTML,
but still impose an aesthetic and a learning curve we don't need.

### Stimulus instead of Alpine
Stimulus is the Rails-world equivalent of Alpine. Reasonable, but
Alpine is closer to the "write the behavior inline near the markup
that needs it" style that pairs well with HTMX.

### htmx-only, no Alpine
Workable for ~80% of the surface area, but the remaining 20% (a
client-side toggle, a tab, a tooltip) is grotesque without Alpine.
Worth the tiny JS dependency.

### Liberator / Yada for content negotiation
Not needed; we serve HTML and a handful of JSON endpoints. Adds
complexity without payoff.

### Carmine / Redis for queues
Adds a second stateful service. Postgres handles our queue load with
`FOR UPDATE SKIP LOCKED`. Revisit if we ever need cross-machine
coordination beyond what one Postgres can do.
