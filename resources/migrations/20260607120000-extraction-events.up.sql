-- Extraction observability: one row per ingest attempt, shaped for an eval
-- dashboard. First-class columns are the axes we aggregate (outcome, per-field
-- provenance source, confidences, counts, timings); the jsonb `provenance` bag
-- holds the full per-field coverage map + body signals.
--
-- Same `--;;` rule as the init migration: one statement per chunk, no trailing
-- `;` (migratus runs them through pgjdbc executeBatch).

CREATE TABLE extraction_events (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  url                 text NOT NULL,
  domain              text,
  outcome             text NOT NULL CHECK (outcome IN ('done','failed')),
  error_class         text,
  extractor           text,
  word_count          int,
  body_confidence     double precision,
  entity_confidence   double precision,
  author_count        int,
  title_source        text,
  author_source       text,
  affiliation_source  text,
  published_source    text,
  fetch_ms            int,
  extract_ms          int,
  provenance          jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at          timestamptz NOT NULL DEFAULT now()
)

--;;

CREATE INDEX extraction_events_created_idx ON extraction_events (created_at DESC)

--;;

CREATE INDEX extraction_events_domain_idx ON extraction_events (domain)
