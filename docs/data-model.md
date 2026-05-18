# Data Model

This is the schema v1 plans to ship. Tables are listed first, the
ER diagram follows, and then notes on the modeling decisions that
aren't obvious from the picture.

The schema lives in Postgres. Migrations are run by Migratus at
startup. Primary keys are UUIDs (`uuid`, generated via `gen_random_uuid()`).
Timestamps are `timestamptz`, default `now()`.

## Tables

### `authors`
The humans (or pseudonymous bylines) who wrote things.

| column      | type        | notes                                    |
| ----------- | ----------- | ---------------------------------------- |
| `id`        | uuid PK     |                                          |
| `name`      | text        | display name                             |
| `sort_name` | text        | for "Last, First" indexing               |
| `slug`      | text UK     | URL slug                                 |
| `bio`       | text NULL   |                                          |
| `created_at`| timestamptz |                                          |
| `updated_at`| timestamptz |                                          |

Notably: **no `email`, no `domain`, no `url`**. Most authors we ingest
will be people we never have direct contact details for. If an author
has a personal site, that's an affiliation, not a column on this table.

### `affiliations`
Publications, blogs, podcasts, newsletters, employers, journals,
preprint servers -- any first-class *thing* an author can be associated
with.

| column        | type        | notes                                                                                          |
| ------------- | ----------- | ---------------------------------------------------------------------------------------------- |
| `id`          | uuid PK     |                                                                                                |
| `name`        | text        | "The Atlantic", "Stratechery", "arXiv"                                                         |
| `slug`        | text UK     |                                                                                                |
| `type`        | text        | enum: `newspaper`, `magazine`, `blog`, `podcast`, `newsletter`, `journal`, `preprint`, `other` |
| `url`         | text NULL   | canonical home page                                                                            |
| `description` | text NULL   |                                                                                                |
| `created_at`  | timestamptz |                                                                                                |
| `updated_at`  | timestamptz |                                                                                                |

### `author_affiliations`
Many-to-many between authors and affiliations.

| column           | type        | notes                                              |
| ---------------- | ----------- | -------------------------------------------------- |
| `author_id`      | uuid FK     | → `authors.id`                                     |
| `affiliation_id` | uuid FK     | → `affiliations.id`                                |
| `role`           | text NULL   | "writer", "editor", "host", "lead", …              |
| `starts_on`      | date NULL   |                                                    |
| `ends_on`        | date NULL   |                                                    |
| `is_primary`     | boolean     | which affiliation we display by default            |

Primary key is `(author_id, affiliation_id, starts_on)` so the same
author can have multiple discrete stints at the same publication.

### `newsletter_sources`
A 1:1 extension table on `affiliations` where `type = 'newsletter'`,
storing newsletter-specific state. Kept separate so the `affiliations`
table stays narrow.

| column                | type        | notes                                                                          |
| --------------------- | ----------- | ------------------------------------------------------------------------------ |
| `affiliation_id`      | uuid PK,FK  | → `affiliations.id`                                                            |
| `inbound_email_alias` | text NULL   | sender pattern (e.g. `@stratechery.com`) we use to recognize incoming issues   |
| `last_seen_at`        | timestamptz |                                                                                |

### Readables: three tables, not one

A "readable" is anything the user can put in their queue. The three
initial types are independent tables. They don't share a base table
because their fields diverge enough that a shared schema would be all
nullable columns.

#### `articles`
The web-page variety.

| column              | type        | notes                                        |
| ------------------- | ----------- | -------------------------------------------- |
| `id`                | uuid PK     |                                              |
| `affiliation_id`    | uuid FK NULL| publisher                                    |
| `title`             | text        |                                              |
| `slug`              | text        |                                              |
| `canonical_url`     | text UK     |                                              |
| `lang`              | text NULL   | BCP-47 code                                  |
| `abstract`          | text NULL   |                                              |
| `body_html`         | text NULL   | extracted reader-view HTML                   |
| `word_count`        | int NULL    |                                              |
| `reading_time_secs` | int NULL    |                                              |
| `published_at`      | timestamptz NULL |                                         |
| `created_at`        | timestamptz |                                              |
| `updated_at`        | timestamptz |                                              |

#### `papers`
Academic/preprint material.

| column           | type        | notes                                  |
| ---------------- | ----------- | -------------------------------------- |
| `id`             | uuid PK     |                                        |
| `affiliation_id` | uuid FK NULL| journal or preprint server             |
| `title`          | text        |                                        |
| `doi`            | text UK NULL|                                        |
| `arxiv_id`       | text UK NULL|                                        |
| `abstract`       | text NULL   |                                        |
| `published_at`   | timestamptz NULL |                                   |
| `pdf_object_key` | text        | key in the R2 bucket                   |
| `created_at`     | timestamptz |                                        |
| `updated_at`     | timestamptz |                                        |

#### `newsletter_issues`
One row per inbound newsletter email.

| column                  | type        | notes                                      |
| ----------------------- | ----------- | ------------------------------------------ |
| `id`                    | uuid PK     |                                            |
| `affiliation_id`        | uuid FK     | the newsletter (an `affiliation`)          |
| `subject`               | text        |                                            |
| `body_html`             | text        | cleaned-up HTML body                       |
| `sent_at`               | timestamptz |                                            |
| `raw_email_object_key`  | text        | key in the R2 bucket for the original .eml |
| `created_at`            | timestamptz |                                            |

### `authorships`
The bridge between readables and authors. Polymorphic on the readable
side: `readable_type` is `'article'` / `'paper'` / `'newsletter_issue'`,
`readable_id` is the matching primary key.

| column              | type        | notes                              |
| ------------------- | ----------- | ---------------------------------- |
| `id`                | uuid PK     |                                    |
| `readable_type`     | text        | discriminator                      |
| `readable_id`       | uuid        | not a FK; application-enforced     |
| `author_id`         | uuid FK     | → `authors.id`                     |
| `ordinal`           | int         | byline order                       |
| `contribution_type` | text NULL   | "primary", "co-author", "editor", …|

There is a partial index per readable type for efficient lookups.

### `users`
End users of the reader. v1 supports a single self-hosted user, but the
schema is designed for multi-tenancy from the start.

| column         | type        | notes                                       |
| -------------- | ----------- | ------------------------------------------- |
| `id`           | uuid PK     |                                             |
| `email`        | citext UK   |                                             |
| `display_name` | text NULL   |                                             |
| `hanko_id`     | text UK NULL| subject identifier from Hanko               |
| `created_at`   | timestamptz |                                             |
| `updated_at`   | timestamptz |                                             |

### `email_inboxes`
The per-user inbound email alias(es) used for newsletter forwarding.
Aliases are random tokens — never derived from a display name or email
— so they're unguessable, don't leak identity to senders, and can be
rotated independently of the user.

| column     | type        | notes                                       |
| ---------- | ----------- | ------------------------------------------- |
| `id`       | uuid PK     |                                             |
| `user_id`  | uuid FK     | → `users.id`                                |
| `alias`    | text UK     | random token, e.g. `r-7f3a9b2c@reader.kira.is` |
| `created_at`| timestamptz|                                             |

### `queue_items`
The reading queue.

| column          | type        | notes                                                       |
| --------------- | ----------- | ----------------------------------------------------------- |
| `id`            | uuid PK     |                                                             |
| `user_id`       | uuid FK     | → `users.id`                                                |
| `readable_type` | text        | matches `authorships.readable_type`                         |
| `readable_id`   | uuid        | application-enforced                                        |
| `via`           | jsonb       | `{:source :email, :raw {…}}` — how this entered the queue   |
| `state`         | text        | enum: `unread`, `reading`, `read`, `archived`               |
| `added_at`      | timestamptz |                                                             |
| `started_at`    | timestamptz NULL |                                                        |
| `finished_at`   | timestamptz NULL |                                                        |

Unique constraint: `(user_id, readable_type, readable_id)` — the same
user can only have one queue item per readable.

### `jobs`
Durable background work.

| column         | type        | notes                                                              |
| -------------- | ----------- | ------------------------------------------------------------------ |
| `id`           | uuid PK     |                                                                    |
| `queue_name`   | text        | `:ingest-email`, `:extract-pdf`, …                                 |
| `payload`      | jsonb       | job-specific args                                                  |
| `state`        | text        | `pending` / `in_progress` / `done` / `failed`                      |
| `attempts`     | int         |                                                                    |
| `last_error`   | text NULL   |                                                                    |
| `run_at`       | timestamptz | scheduled run time (now() for immediate)                           |
| `locked_until` | timestamptz NULL | held by a worker until this time                              |
| `created_at`   | timestamptz |                                                                    |
| `updated_at`   | timestamptz |                                                                    |

Workers do `SELECT ... FOR UPDATE SKIP LOCKED` to claim a job.

## ER diagram

```mermaid
erDiagram
  authors {
    uuid id PK
    text name
    text slug UK
    text sort_name
  }

  affiliations {
    uuid id PK
    text name
    text slug UK
    text type "newspaper|blog|podcast|newsletter|..."
    text url
  }

  author_affiliations {
    uuid author_id FK
    uuid affiliation_id FK
    text role
    date starts_on
    date ends_on
    bool is_primary
  }

  newsletter_sources {
    uuid affiliation_id PK "also FK to affiliations.id"
    text inbound_email_alias
    timestamptz last_seen_at
  }

  articles {
    uuid id PK
    uuid affiliation_id FK
    text title
    text canonical_url UK
    text body_html
    int reading_time_secs
    timestamptz published_at
  }

  papers {
    uuid id PK
    uuid affiliation_id FK
    text title
    text doi UK
    text arxiv_id UK
    text pdf_object_key
    timestamptz published_at
  }

  newsletter_issues {
    uuid id PK
    uuid affiliation_id FK
    text subject
    text body_html
    text raw_email_object_key
    timestamptz sent_at
  }

  authorships {
    uuid id PK
    text readable_type "article|paper|newsletter_issue"
    uuid readable_id
    uuid author_id FK
    int ordinal
    text contribution_type
  }

  users {
    uuid id PK
    text email UK
    text display_name
    text hanko_id UK
  }

  email_inboxes {
    uuid id PK
    uuid user_id FK
    text alias UK
  }

  queue_items {
    uuid id PK
    uuid user_id FK
    text readable_type
    uuid readable_id
    jsonb via
    text state "unread|reading|read|archived"
    timestamptz added_at
  }

  jobs {
    uuid id PK
    text queue_name
    jsonb payload
    text state
    int attempts
    timestamptz run_at
    timestamptz locked_until
  }

  authors ||--o{ author_affiliations : "writes for"
  affiliations ||--o{ author_affiliations : "has"
  affiliations ||--o| newsletter_sources : "may be a newsletter"
  affiliations ||--o{ articles : "publishes"
  affiliations ||--o{ papers : "hosts"
  affiliations ||--o{ newsletter_issues : "sends"
  authors ||--o{ authorships : "wrote"
  articles ||--o{ authorships : "polymorphic"
  papers ||--o{ authorships : "polymorphic"
  newsletter_issues ||--o{ authorships : "polymorphic"
  users ||--o{ email_inboxes : "owns"
  users ||--o{ queue_items : "queues"
  articles ||--o{ queue_items : "polymorphic"
  papers ||--o{ queue_items : "polymorphic"
  newsletter_issues ||--o{ queue_items : "polymorphic"
```

## Modeling notes

### Why three readable tables, not one
See the principles doc, point 9. Articles, papers, and newsletter
issues have substantially different fields and substantially different
sources of truth. Putting them all in one `readables` table would force
many nullable columns and bury type-discrimination logic in application
code. With three tables, every type-specific query is a `SELECT *
FROM papers WHERE …`, every cross-cutting query is a `UNION` or a
domain function over typed inputs.

### Why affiliations are first-class
An affiliation is a noun with its own identity, URL, type, and
relationship to multiple authors and multiple publications. It outlives
any individual article. This is modeled as a table from the start to
accommodate authors who write for multiple outlets, which is most of them.
We get arbitrary outlets per author and arbitrary authors per outlet for free.

### Polymorphic references, not table inheritance
`authorships.readable_type` + `readable_id` is a polymorphic reference.
Postgres can't enforce it as a foreign key. The integrity check lives
in the application:

- inserts go through a domain function that validates the type/id pair
- the worker only writes types we know about
- a periodic reconciliation job *could* sweep for orphans, but in
  practice we never get them because every insert path is controlled

The alternative — three `authorships_*` tables — works but explodes
the join surface (an author's bibliography becomes a UNION across
three tables for every query). Polymorphic FK plus an
application-enforced check is the better trade.

### Why `via` is a jsonb blob
The way a readable got into the queue is meaningful context but doesn't
need to be queried structurally. We store it as `jsonb` so we can
add new source types without migrating. A queue item from email looks
like:

```json
{"source": "email", "alias": "r-7f3a9b2c@reader.kira.is",
 "from": "ben@stratechery.com", "subject": "…"}
```

one found via some other blog looks like:

```json
{"source": "https://some-blog.com"}
```

If a `via.source` value ever needs to be filtered on aggressively,
that's a sign to pull it out into a real column.

### Indexes (planned for v1)
- `authors(slug)` unique
- `affiliations(slug)` unique
- `affiliations(type)` for type-filtered listings
- `articles(canonical_url)` unique
- `papers(doi)` partial unique where not null
- `papers(arxiv_id)` partial unique where not null
- `authorships(readable_type, readable_id)` for "who wrote X"
- `authorships(author_id)` for "what has X written"
- `queue_items(user_id, state, added_at desc)` for the queue view
- `jobs(state, run_at)` where `state = 'pending'` for the worker poll
