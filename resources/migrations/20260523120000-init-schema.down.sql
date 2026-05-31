DROP TABLE IF EXISTS jobs
--;;
DROP TABLE IF EXISTS queue_items
--;;
DROP TABLE IF EXISTS email_inboxes
--;;
DROP TABLE IF EXISTS users
--;;
DROP TABLE IF EXISTS authorships
--;;
DROP TABLE IF EXISTS newsletter_issues
--;;
DROP TABLE IF EXISTS papers
--;;
DROP TABLE IF EXISTS articles
--;;
DROP TABLE IF EXISTS newsletter_sources
--;;
DROP TABLE IF EXISTS author_affiliations
--;;
DROP TABLE IF EXISTS affiliations
--;;
-- The citext extension (created in the up migration) is intentionally
-- left installed: it is database-wide, may back objects outside this
-- migration, and the up side uses IF NOT EXISTS so a re-up is a no-op.
DROP TABLE IF EXISTS authors
