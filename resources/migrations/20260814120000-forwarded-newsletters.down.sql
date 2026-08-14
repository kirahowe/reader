DROP TABLE IF EXISTS newsletter_source_aliases

--;;

DROP INDEX IF EXISTS newsletter_issues_original_message_id_idx

--;;

ALTER TABLE newsletter_issues
  DROP COLUMN IF EXISTS updated_at,
  DROP COLUMN IF EXISTS extraction_provenance,
  DROP COLUMN IF EXISTS extraction_version,
  DROP COLUMN IF EXISTS is_forwarded,
  DROP COLUMN IF EXISTS original_url,
  DROP COLUMN IF EXISTS original_from_email,
  DROP COLUMN IF EXISTS original_from_name,
  DROP COLUMN IF EXISTS original_message_id
