DROP INDEX IF EXISTS newsletter_issues_message_id_idx

--;;

ALTER TABLE newsletter_issues DROP COLUMN IF EXISTS message_id
