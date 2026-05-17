# ADR 0001: Server stack and UI approach

- **Status**: Accepted
- **Date**: 2026-05-17

## Context

Reader is a server-rendered, content-heavy web app: a reading queue and
library backed by Postgres, with PDF and email ingestion. The UI is
mostly hypertext with islands of light interactivity, not an SPA. The
stack picks need to compose into something that stays small and
introspectable as it grows.

## Decision

| Layer                   | Choice                              |
| ----------------------- | ----------------------------------- |
| HTTP server             | http-kit                            |
| Routing                 | Reitit                              |
| Validation / coercion   | Malli                               |
| HTML templating         | Hiccup v2                           |
| Client interactivity    | HTMX + Alpine.js                    |
| CSS                     | Vanilla, design-token-driven        |
| Background work         | core.async + a durable jobs table   |
| In-memory caches        | clojure.core.cache                  |
| Structured logging      | mu/log                              |
| Tests                   | kaocha                              |
| Build                   | tools.build                         |

## Rationale

**Data-driven HTTP.** Reitit's routes are values, its middleware
composes per-route, and its Malli integration removes the boundary
between routing and validation. The router is the API description; no
parallel OpenAPI spec to keep in sync.

**Schemas as values.** Malli schemas round-trip through EDN. The same
schema validates an inbound webhook, generates test data, and produces
JSON Schema for clients.

**HTML as data.** Hiccup gives the full Clojure language inside views.
Helpers, partials, and abstractions are written with the same tools as
the rest of the code. No template-language quirks; no separate
preprocessor.

**HTMX with Alpine for the small pieces.** Server-rendered HTML
returned from focused endpoints, swapped into the page by HTMX, covers
the bulk of the UI without a build step, hydration, or a client-side
router. Alpine handles the local interactivity (toggles, tabs,
dropdowns) that's awkward in raw HTML.

**Vanilla CSS, no framework.** Utility-class systems entangle markup
with styling and make HTML unreadable without the stylesheet. CSS
custom properties for tokens, element selectors for the bulk of the
look, classes only for genuinely semantic variants. The aesthetic
target — minimalist Scandinavian, generous whitespace, real typography,
restrained colour — is straightforward to express directly.

**Durable jobs over an in-memory queue.** Work that matters across a
process restart (sending an email, parsing a PDF, ingesting a
newsletter) belongs in a Postgres `jobs` table drained by workers using
`SELECT ... FOR UPDATE SKIP LOCKED`. In-memory `chan`s are reserved
for transient coordination inside a single request.

**Structured logs.** mu/log events are maps, not formatted strings.
Pretty in dev, JSON in prod. The publisher is an Integrant component.

## Consequences

A new view is a Reitit route + a Malli schema + a Hiccup function +
optional HTMX attributes. A new endpoint shaped like that takes
minutes, reads as data, and is testable as a plain function call.

There is no build step for the frontend. Asset pipeline is "put the
file in `resources/public/`."

Durable jobs cost one Postgres table and a worker loop, not a separate
queue service.
