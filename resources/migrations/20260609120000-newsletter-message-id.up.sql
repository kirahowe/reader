-- Idempotency key for inbound newsletter issues: the email Message-ID. A
-- redelivered email (Cloudflare retrying the webhook, or a resend) must not
-- create a second issue, so :ingest-email dedupes on this. Nullable — issues
-- created by other means (seeds) need not carry one — with a partial UNIQUE
-- index so only present message-ids are deduped.
--
-- One statement per --;; chunk, no trailing ; (see the init migration).

ALTER TABLE newsletter_issues ADD COLUMN message_id text

--;;

CREATE UNIQUE INDEX newsletter_issues_message_id_idx
  ON newsletter_issues (message_id) WHERE message_id IS NOT NULL
