# ADR 0001: Clojure on the JVM, Java 25 LTS

- **Status**: Accepted
- **Date**: 2026-05-16
- **Supersedes**: —
- **Superseded by**: —

## Context

Reader is a personal reading app: a content-heavy, mostly server-rendered
web application with background ingestion of articles, papers, and
newsletters. It is built and operated by a small team (often one
person), needs to be cheap to host, and needs to be pleasant to evolve
over years. We expect to spend more time reading and modifying this code
than writing it.

The realistic candidate languages were:

- **Clojure / ClojureScript** (the author's primary language, large
  shared knowledge with the personal-projects ecosystem)
- **TypeScript on Node** (vast ecosystem, but heavy maintenance footprint
  and a strong cultural pull toward SPAs we don't want)
- **Go** (excellent ops story, but verbose for this kind of work and
  weaker on data manipulation)
- **Ruby on Rails** (great for content apps, but the host ecosystem is
  moving away from it and we lose the JVM)

## Decision

Reader is written in **Clojure 1.12** running on **Java 25** (the
September 2025 LTS release). The production runtime is
`eclipse-temurin:25-jre` in a container.

We use Clojure on the JVM specifically (not Babashka, not ClojureScript)
for the server. Babashka is used for *developer tooling* (the contents
of `bb.edn`) but the application itself is a regular JVM process.

## Consequences

### What we gain
- A small, expressive language we already write fluently.
- The JVM ecosystem: mature drivers (next.jdbc, S3 SDK), reliable
  GC, observability tooling, a 25-year-old runtime we can trust.
- A REPL-driven workflow: integrant + the dev profile mean a running
  system can be inspected and modified live, which dwarfs the
  productivity of any compiled/restart language for this kind of work.
- Long-term LTS support on Java 25, freeing us from version churn.
- A single language across server, build (`tools.build`), and tasks
  (Babashka).

### What we accept
- JVM cold-start time (~1–2s) means we are not a serverless workload.
  This is fine: we run on a long-lived Fly machine.
- A 60–80 MB container image floor for the JRE alone. Acceptable for an
  app at this scale.
- Some libraries we want are JVM-Java rather than Clojure-native. We
  reach for them when needed (S3 SDK, hikari) and wrap them thinly.
- The talent pool for Clojure is smaller than for TS/Python; this is a
  personal project so it does not bite.

## Alternatives considered

### TypeScript + Hono/Fastify on Node
Rejected: TS at this scale spends too much time fighting build
tooling, type-system corners, and ecosystem churn. The async model
makes background work and REPL-style introspection awkward.

### Go
Rejected: verbose for ingestion-heavy code. We will manipulate a lot
of email/paper/article shapes, and Go's struct-and-method ergonomics
fight us where Clojure helps us. Also: no REPL.

### Phoenix / Elixir
Considered seriously. Excellent fit on paper. Rejected because we don't
already speak it well and the personal cost of becoming proficient
exceeds the technical gain.

### Babashka all the way down
Considered for the simplicity. Rejected because the application needs
the full JVM ecosystem (Postgres driver, S3 SDK, mu/log publishers,
long-lived connection pools, real concurrency primitives via
core.async). Babashka stays in its lane: developer tooling.

### Clojure on GraalVM native image
Rejected for v1. Native image gives us fast cold start at the cost of
slower steady-state throughput, longer build times, and a wall of
reflection-config maintenance. Worth revisiting if we ever genuinely
need serverless deployment.
