-- Persist the normalized newsletter identity independently from its delivery
-- envelope, and record enough extraction state to re-run newer parser versions
-- safely. message_id remains the outer delivery Message-ID (the idempotency key);
-- original_message_id is the newsletter's own id when MIME preserved it.

ALTER TABLE newsletter_issues
  ADD COLUMN original_message_id text,
  ADD COLUMN original_from_name text,
  ADD COLUMN original_from_email text,
  ADD COLUMN original_url text,
  ADD COLUMN is_forwarded boolean NOT NULL DEFAULT false,
  ADD COLUMN extraction_version int NOT NULL DEFAULT 1,
  ADD COLUMN extraction_provenance jsonb NOT NULL DEFAULT '{}'::jsonb,
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now()

--;;

CREATE INDEX newsletter_issues_original_message_id_idx
  ON newsletter_issues (original_message_id) WHERE original_message_id IS NOT NULL

--;;

-- A publication can send from several domains/addresses. Keep aliases as a
-- many-to-one relation instead of overwriting newsletter_sources' single legacy
-- field whenever a second valid sender appears.
CREATE TABLE newsletter_source_aliases (
  alias           text PRIMARY KEY,
  affiliation_id  uuid NOT NULL REFERENCES affiliations (id) ON DELETE CASCADE,
  last_seen_at    timestamptz NOT NULL DEFAULT now()
)

--;;

CREATE INDEX newsletter_source_aliases_affiliation_idx
  ON newsletter_source_aliases (affiliation_id)

--;;

INSERT INTO newsletter_source_aliases (alias, affiliation_id, last_seen_at)
SELECT inbound_email_alias, affiliation_id, COALESCE(last_seen_at, now())
FROM newsletter_sources
WHERE inbound_email_alias IS NOT NULL
ON CONFLICT (alias) DO NOTHING
