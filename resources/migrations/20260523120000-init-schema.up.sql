-- v1 schema: every table, index, and constraint Reader needs at the
-- end of step 2. Layout follows docs/data-model.md.
--
-- Each `--;;` chunk holds exactly one statement with NO trailing `;` —
-- migratus runs them through pgjdbc's executeBatch, which throws
-- "Too many update results were returned" otherwise (issue #42).

CREATE EXTENSION IF NOT EXISTS citext

--;;

-- ============================================================
-- People + places
-- ============================================================

CREATE TABLE authors (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name        text NOT NULL,
  -- Nullable on purpose: the "Last, First" collation key is optional.
  -- reader.authors/create! fills it from `name` only for unambiguous
  -- "First Last" bylines; anything else is left NULL and sorting falls
  -- back to `name` via COALESCE. A future UI will let the user set it.
  sort_name   text,
  slug        text NOT NULL UNIQUE,
  bio         text,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
)

--;;

CREATE TABLE affiliations (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name         text NOT NULL,
  slug         text NOT NULL UNIQUE,
  type         text NOT NULL CHECK (type IN ('newspaper','magazine','blog','podcast',
                                              'newsletter','journal','preprint','other')),
  url          text,
  description  text,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now()
)

--;;

CREATE INDEX affiliations_type_idx ON affiliations (type)

--;;

-- A stint of an author writing for an affiliation. Synthetic id PK so
-- the conceptual key (author, affiliation, starts_on) can include a
-- nullable starts_on; UNIQUE NULLS NOT DISTINCT forbids duplicates.
CREATE TABLE author_affiliations (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  author_id       uuid NOT NULL REFERENCES authors (id) ON DELETE CASCADE,
  affiliation_id  uuid NOT NULL REFERENCES affiliations (id) ON DELETE CASCADE,
  role            text,
  starts_on       date,
  ends_on         date,
  is_primary      boolean NOT NULL DEFAULT false,
  UNIQUE NULLS NOT DISTINCT (author_id, affiliation_id, starts_on)
)

--;;

-- 1:1 extension on affiliations where type = 'newsletter'. The
-- application is responsible for keeping the affiliation type in sync.
CREATE TABLE newsletter_sources (
  affiliation_id        uuid PRIMARY KEY REFERENCES affiliations (id) ON DELETE CASCADE,
  inbound_email_alias   text,
  last_seen_at          timestamptz
)

--;;

-- ============================================================
-- Readables: articles, papers, newsletter issues
-- ============================================================

CREATE TABLE articles (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  affiliation_id      uuid REFERENCES affiliations (id) ON DELETE SET NULL,
  title               text NOT NULL,
  slug                text NOT NULL,
  canonical_url       text NOT NULL UNIQUE,
  lang                text,
  abstract            text,
  body_html           text,
  word_count          int,
  reading_time_secs   int,
  published_at        timestamptz,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now()
)

--;;

CREATE TABLE papers (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  affiliation_id  uuid REFERENCES affiliations (id) ON DELETE SET NULL,
  title           text NOT NULL,
  doi             text,
  arxiv_id        text,
  abstract        text,
  published_at    timestamptz,
  pdf_object_key  text NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
)

--;;

CREATE UNIQUE INDEX papers_doi_idx ON papers (doi) WHERE doi IS NOT NULL

--;;

CREATE UNIQUE INDEX papers_arxiv_id_idx ON papers (arxiv_id) WHERE arxiv_id IS NOT NULL

--;;

CREATE TABLE newsletter_issues (
  id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  affiliation_id         uuid NOT NULL REFERENCES affiliations (id) ON DELETE RESTRICT,
  subject                text NOT NULL,
  body_html              text NOT NULL,
  sent_at                timestamptz,
  raw_email_object_key   text NOT NULL,
  created_at             timestamptz NOT NULL DEFAULT now()
)

--;;

-- ============================================================
-- Authorships: polymorphic bridge readables <-> authors
-- ============================================================

CREATE TABLE authorships (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  readable_type       text NOT NULL CHECK (readable_type IN ('article','paper','newsletter_issue')),
  readable_id         uuid NOT NULL,
  author_id           uuid NOT NULL REFERENCES authors (id) ON DELETE CASCADE,
  ordinal             int  NOT NULL DEFAULT 0,
  contribution_type   text
)

--;;

CREATE INDEX authorships_readable_idx ON authorships (readable_type, readable_id)

--;;

CREATE INDEX authorships_author_idx ON authorships (author_id)

--;;

-- ============================================================
-- Users + their inbound aliases
-- ============================================================

CREATE TABLE users (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email         citext NOT NULL UNIQUE,
  display_name  text,
  hanko_id      text UNIQUE,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
)

--;;

CREATE TABLE email_inboxes (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  alias       text NOT NULL UNIQUE,
  created_at  timestamptz NOT NULL DEFAULT now()
)

--;;

-- ============================================================
-- The reading queue (polymorphic to readables)
-- ============================================================

CREATE TABLE queue_items (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  readable_type text NOT NULL CHECK (readable_type IN ('article','paper','newsletter_issue')),
  readable_id   uuid NOT NULL,
  via           jsonb NOT NULL DEFAULT '{}'::jsonb,
  state         text NOT NULL DEFAULT 'unread'
                CHECK (state IN ('unread','reading','read','archived')),
  added_at      timestamptz NOT NULL DEFAULT now(),
  started_at    timestamptz,
  finished_at   timestamptz,
  UNIQUE (user_id, readable_type, readable_id)
)

--;;

CREATE INDEX queue_items_user_state_idx
  ON queue_items (user_id, state, added_at DESC)

--;;

-- ============================================================
-- Durable background jobs
-- ============================================================

CREATE TABLE jobs (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  queue_name    text NOT NULL,
  payload       jsonb NOT NULL DEFAULT '{}'::jsonb,
  state         text NOT NULL DEFAULT 'pending'
                CHECK (state IN ('pending','in_progress','done','failed')),
  attempts      int NOT NULL DEFAULT 0,
  last_error    text,
  run_at        timestamptz NOT NULL DEFAULT now(),
  locked_until  timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
)

--;;

CREATE INDEX jobs_pending_idx ON jobs (state, run_at) WHERE state = 'pending'
