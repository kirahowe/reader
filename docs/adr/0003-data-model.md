# ADR 0003: Data model

- **Status**: Accepted
- **Date**: 2026-05-17

## Context

Reader has three pivots:

- **People** who write things (authors).
- **Places** they write (publications, blogs, journals, podcasts,
  newsletters — *affiliations*).
- **Things** they wrote (articles, papers, newsletter issues).

…plus per-user state (reading queue, inbound email aliases) and
durable background work.

The schema needs to support querying along every axis: "all papers by
author X this year", "all articles from this newsletter", "all
authors who write for this affiliation", "all readables a user has
queued this month". Each of those is a one-liner against a normalized
schema and an ordeal against a denormalized one.

## Decision

Postgres. UUIDs for primary keys, `timestamptz` for everything time.
Migrations live in `resources/migrations/` and run via Migratus at
startup.

The conceptual shape:

- `authors` — first-class. No email, no domain, no url. Personal sites
  belong to affiliations, not to the author row.
- `affiliations` — first-class. Newspapers, magazines, blogs, podcasts,
  newsletters, journals, preprint servers, employers. Has a `type`,
  a `url`, a `slug`.
- `author_affiliations` — many-to-many bridge with `role`, `starts_on`,
  `ends_on`, `is_primary`.
- `newsletter_sources` — 1:1 extension on `affiliations` where
  `type = 'newsletter'`. Keeps newsletter-specific fields out of the
  main affiliations table.
- **Three readable tables**, not one: `articles`, `papers`,
  `newsletter_issues`. No shared base table.
- `authorships` — polymorphic bridge readables ↔ authors via
  `(readable_type, readable_id, author_id)`.
- `users`, `email_inboxes` — multi-tenant from the start. The
  per-user inbound alias is a random token (e.g.
  `r-7f3a9b2c@reader.kira.is`), never derived from a username.
- `queue_items` — the reading queue. Polymorphic to readables. `via`
  is a `jsonb` blob carrying source attribution.
- `jobs` — durable background work, drained with `SELECT … FOR UPDATE
  SKIP LOCKED`.

Full table definitions, column types, and ER diagram in
[`data-model.md`](../data-model.md).

## Rationale

**Three readable tables.** Articles, papers, and newsletter issues
diverge sharply in shape. An article has a canonical URL and an
extracted reading-time estimate; a paper has a DOI or arXiv ID and a
PDF in R2; a newsletter issue has a subject line, an HTML body, and a
raw `.eml`. Forcing them into one row with twenty nullable columns
buries type discrimination in application code and makes every
type-specific query a `WHERE type = ...` instead of a `SELECT FROM`.

**Affiliations as a table.** An affiliation has its own identity, URL,
type, and relationship to multiple authors and multiple readables. It
outlives any single article. The first author who writes for multiple
outlets — which is most of them — breaks any model that puts the
publication on the author row.

**Polymorphic FKs.** `authorships.readable_id` and
`queue_items.readable_id` reference one of several readable tables.
Postgres can't enforce the FK; the application enforces it through
controlled insert paths. The alternative — three `authorships_*`
tables — turns every author bibliography into a UNION across three
tables and gains nothing.

**Random inbound aliases.** Per-user newsletter aliases are random
tokens. They aren't guessable from a display name, they don't leak
identity to a sender, and they can be rotated without renaming the
user.

**`via` as jsonb.** The provenance of a queue item ("came in by
email", "added manually", "imported from RSS later") is meaningful
context but not queried structurally. Storing it as `jsonb` lets new
source types appear without a migration. If a `via` field ever needs
to be filtered on at scale, it gets promoted to a column.

## Consequences

Every query the application currently knows it will run is a clean
piece of SQL. Cross-type queries (the reading queue, which can hold
any readable type) become UNIONs hidden behind domain functions;
callers ask for "this user's queue", not for "articles UNION papers
UNION newsletter_issues".

Adding a new content type (podcasts, video) is a new table plus
extending the `readable_type` enum. No migration of a shared row
shape.
