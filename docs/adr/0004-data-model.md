# ADR 0004: Data model — Postgres, polymorphic readables, first-class affiliations

- **Status**: Accepted
- **Date**: 2026-05-16

## Context

Reader's domain has three pivots:

1. The **people** who write things (authors).
2. The **places** they write (publications, blogs, journals, podcasts,
   newsletters).
3. The **things** they wrote (articles, papers, newsletter issues).

…plus orthogonally: per-user state (reading queue, mark-as-read,
inbound email aliases) and durable infrastructure (background jobs).

We need a schema that:

- Lets us ask any question about any axis. ("All papers by author X
  this year." "Most-read newsletter affiliations." "Articles published
  by Z affiliation since I last read one.")
- Doesn't force NULLs on fields that only apply to a subset of types.
- Accommodates the messy reality that the same author writes for many
  outlets and the same outlet has many authors.
- Stays simple to operate. We don't want to run two storage engines
  in v1.

## Decision

**Postgres** is the single storage system for structured data.
**Cloudflare R2** stores blob content (PDFs, raw `.eml` files) — its
keys are referenced from Postgres rows.

The conceptual schema:

- `authors` — a first-class entity. No `email`, no `domain`, no `url`.
  Personal-site URLs and pen-name-vs-real-name complexities live in
  affiliations.
- `affiliations` — a first-class entity. Newspapers, magazines, blogs,
  podcasts, newsletters, journals, preprint servers, employers. Has
  `type`, `url`, `name`, `slug`.
- `author_affiliations` — many-to-many bridge with `role`,
  `starts_on`, `ends_on`, `is_primary`.
- `newsletter_sources` — a 1:1 extension on affiliations whose type is
  `newsletter`. Keeps newsletter-specific fields (inbound alias, etc.)
  out of the main affiliations table.
- **Three readable tables**, not one: `articles`, `papers`,
  `newsletter_issues`. Each has its own shape. There is no shared base
  table.
- `authorships` — a polymorphic bridge between readables and authors,
  via `(readable_type, readable_id)`. Application-enforced integrity.
- `users`, `email_inboxes` — multi-tenancy-ready from the start.
- `queue_items` — the reading queue, also polymorphic on the readable
  side. `via` is a `jsonb` blob carrying source attribution.
- `jobs` — durable background work, drained by workers using `SELECT …
  FOR UPDATE SKIP LOCKED`.

Full schema and ER diagram in [`data-model.md`](../data-model.md).

## Consequences

### What we gain
- Every question we currently know we'll ask is a clean SQL query.
- No nullable explosion across heterogeneous content types.
- An author can have arbitrary outlets and an outlet can have
  arbitrary authors, modelled honestly.
- One storage system for v1: easier ops, easier backups, fewer
  moving parts. Postgres handles our queue load comfortably.
- Adding a new content type (podcast episodes, video) means a new
  table plus extending the `readable_type` enum — no schema migration
  to a shared row shape.

### What we accept
- Application-enforced referential integrity for polymorphic FKs
  (`authorships.readable_id`, `queue_items.readable_id`). Inserts go
  through controlled paths; a periodic reconciliation job is available
  if we ever need it.
- Cross-type queries occasionally become UNIONs. We hide this behind
  domain functions; callers ask "what's in this user's queue?", not
  "give me articles UNION papers UNION newsletter_issues."
- A normalized schema has more JOINs than a denormalized one. We'll
  index for our access patterns and revisit if we find a real
  bottleneck.

## Alternatives considered

### Datalevin / Datomic / a graph database
The graph case for this model is real: authors–affiliations–readables
forms a natural graph and we'd love to query it with logic variables.
Rejected for v1 because:

- The user is much more familiar with Postgres. Operational comfort
  matters.
- Datalevin specifically would add a new storage system to the deploy.
- The interesting graph queries (e.g. "authors who co-write with X")
  are still trivially expressible in SQL at our scale.
- The reasons to want normalized authors and affiliations — search,
  recommendations, byline reuse — are the *same* reasons either
  storage type would shine. Postgres pays them with familiar tools.

If, later, the graph queries become the centre of gravity (e.g. an
author-suggestion engine), we can extract that surface to a graph
store while leaving the canonical schema in Postgres.

### One `readables` table with `type` and `payload jsonb`
Considered briefly and rejected. Forces every type-specific predicate
into application code, makes indexing painful, makes evolution of any
one type's schema a full-table concern, and reads less well.

### One `readables` base table with per-type child tables (PostgreSQL inheritance / class-table inheritance)
Rejected. PostgreSQL inheritance has well-known gotchas (FKs don't
descend, partitioning is the supported substitute). Class-table
inheritance (a base table plus a child table per type, sharing a PK)
costs us a JOIN for every read and gives back very little — the only
thing it really gains is one common primary-key space for polymorphic
references, which we don't need badly enough.

### `content_hash` column on every readable
Earlier drafts had this. Removed because:

- Canonical URL (articles), DOI/arXiv ID (papers), and Message-ID
  (newsletter issues) are stronger identifiers than a body-content
  hash.
- A hash has to be kept up to date, which is one more thing to forget.
- We have no use case that needs it today. We can add it later if
  cross-source dedup becomes a real need.

### Putting newsletter fields on `affiliations`
Tempting because every newsletter *is* an affiliation. Rejected
because it widens the affiliations table with columns that only apply
when `type = 'newsletter'`, and it makes other affiliation types
(journals, magazines) drag those fields along as NULL. The 1:1
extension table is cleaner.

### Redis or RabbitMQ for the jobs queue
Postgres + `FOR UPDATE SKIP LOCKED` is sufficient through several
orders of magnitude beyond what Reader will ever do. We avoid a second
stateful service for v1.
