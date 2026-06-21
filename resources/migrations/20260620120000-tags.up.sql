-- Auto-tagging: a shared tag vocabulary, the per-readable baseline assigned
-- by the infer-tags abstraction, per-user overrides on a queue item, readable
-- embeddings, and a tagging eval table.
--
-- Embeddings are stored as jsonb arrays of floats, not pgvector: similarity is
-- brute-forced in Clojure (sub-100ms at this scale) and Zonky embedded-postgres
-- (dev/test) has no vector extension. The jsonb codec in reader.db.types
-- round-trips them with no extra machinery.
--
-- Same `--;;` rule as the other migrations: one statement per chunk, no
-- trailing `;`.

CREATE TABLE tags (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  slug        text NOT NULL UNIQUE,
  label       text NOT NULL,
  -- Embedding of the label, used to fold a newly-proposed near-duplicate into
  -- an existing tag (cosine >= threshold). Nullable: a tag can exist before its
  -- embedding is computed, or if the embedder is unavailable.
  embedding   jsonb,
  created_at  timestamptz NOT NULL DEFAULT now()
)

--;;

-- The shared baseline: which tags the categorizer assigned to a readable.
-- Intrinsic to the content, so keyed on the readable (shared across users),
-- not the queue item. readable_type mirrors authorships.readable_type.
CREATE TABLE readable_tags (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tag_id         uuid NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
  readable_type  text NOT NULL CHECK (readable_type IN ('article','paper','newsletter_issue')),
  readable_id    uuid NOT NULL,
  confidence     double precision NOT NULL DEFAULT 1.0,
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tag_id, readable_type, readable_id)
)

--;;

CREATE INDEX readable_tags_readable_idx ON readable_tags (readable_type, readable_id)

--;;

CREATE INDEX readable_tags_tag_idx ON readable_tags (tag_id)

--;;

-- Per-user override: a sparse delta on top of the shared baseline.
-- op='add' pins a tag the baseline lacks; op='suppress' hides a baseline tag.
-- Effective tags = (baseline \ suppressions) ∪ additions.
CREATE TABLE queue_item_tags (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  queue_item_id  uuid NOT NULL REFERENCES queue_items (id) ON DELETE CASCADE,
  tag_id         uuid NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
  op             text NOT NULL CHECK (op IN ('add','suppress')),
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (queue_item_id, tag_id)
)

--;;

CREATE INDEX queue_item_tags_queue_item_idx ON queue_item_tags (queue_item_id)

--;;

-- One embedding per readable, for phase-2 "more like this" / recommendations.
-- Populated now so the corpus needn't be reprocessed later.
CREATE TABLE readable_embeddings (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  readable_type  text NOT NULL CHECK (readable_type IN ('article','paper','newsletter_issue')),
  readable_id    uuid NOT NULL,
  embedding      jsonb NOT NULL,
  model          text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (readable_type, readable_id)
)

--;;

-- Tagging observability: one row per tag-readable attempt, shaped like
-- extraction_events. First-class columns are the axes we aggregate; the jsonb
-- provenance bag holds the model response + vocab snapshot for offline evals
-- and local-model comparison.
CREATE TABLE tagging_events (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  readable_type  text NOT NULL,
  readable_id    uuid NOT NULL,
  outcome        text NOT NULL CHECK (outcome IN ('done','failed','skipped')),
  error_class    text,
  model          text,
  tag_count      int,
  duration_ms    int,
  provenance     jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at     timestamptz NOT NULL DEFAULT now()
)

--;;

CREATE INDEX tagging_events_readable_idx ON tagging_events (readable_type, readable_id)

--;;

CREATE INDEX tagging_events_created_idx ON tagging_events (created_at DESC)
